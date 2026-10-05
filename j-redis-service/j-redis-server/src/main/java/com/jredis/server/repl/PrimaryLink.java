package com.jredis.server.repl;

import com.jredis.common.RespProtocolException;
import com.jredis.common.RespRequestParser;
import com.jredis.common.RespWriter;
import com.jredis.server.core.Engine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A replica's connection to its primary (docs/16-replication.md §5, §6), on the link's own I/O
 * thread: the handshake, the image written to a file as it arrives, then the stream decoded and
 * handed to the command thread in order. One per attempt: after any failure, a new one.
 */
final class PrimaryLink {

    private static final Logger log = LoggerFactory.getLogger(PrimaryLink.class);
    private static final int MARK = 40;
    private static final int MAX_LINE = 64 * 1024;
    private static final long HIGH_BYTES = 64L * 1024 * 1024;
    private static final long LOW_BYTES = 8L * 1024 * 1024;

    /** One command of the stream and its size in the stream. */
    record Record(byte[][] argv, long bytes) { }

    private enum State { HANDSHAKE, IMAGE_HEADER, IMAGE, LOADING, STREAM, CLOSED }

    private final Engine engine;
    private final Replication replication;
    private final String host;
    private final int port;
    private final Path dir;
    private final Deque<byte[][]> steps = new ArrayDeque<>();
    private final AtomicLong inFlight = new AtomicLong();
    /** The offset this replica has applied, for its acknowledgements: set by the command thread. */
    private volatile long appliedOffset;
    /** When a byte last came from the primary. */
    private volatile long lastReadMillis = System.currentTimeMillis();

    private volatile Channel channel;

    // the link's I/O thread only
    private volatile State state = State.HANDSHAKE;
    private io.netty.util.concurrent.ScheduledFuture<?> acks;
    private byte[][] awaiting;
    private ByteBuf acc;
    private String replid;
    private long offset;
    private long primaryEpoch;
    private EndMark endMark;
    private FileChannel file;
    private Path filePath;
    private final RespRequestParser parser = new RespRequestParser(Integer.MAX_VALUE - 16, 0, false);
    private long recordBytes;
    private volatile boolean paused;

    /**
     * @param historyReplid the history this replica holds, or null: it then asks for a full sync
     * @param historyOffset how much of that history it has applied
     */
    PrimaryLink(Engine engine, Replication replication, String host, int port, Path dir, String auth, int listeningPort,
                long epoch, String historyReplid, long historyOffset) {
        this.engine = engine;
        this.replication = replication;
        this.host = host;
        this.port = port;
        this.dir = dir;
        if (!auth.isEmpty()) {
            steps.add(words("AUTH", auth));
        }
        steps.add(words("REPLCONF", "listening-port", Integer.toString(listeningPort)));
        steps.add(words("REPLCONF", "epoch", Long.toString(epoch)));            // an older primary refuses (D-37)
        if (historyReplid == null) {
            steps.add(words("PSYNC", "?", "-1"));
        } else {
            steps.add(words("PSYNC", historyReplid, Long.toString(historyOffset)));   // continue, if the backlog still holds it
        }
        this.appliedOffset = historyOffset;
    }

    private static byte[][] words(String... w) {
        byte[][] out = new byte[w.length][];
        for (int i = 0; i < w.length; i++) {
            out[i] = w[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    String address() {
        return host + ":" + port;
    }

    void connect(EventLoopGroup group) {
        new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new Handler());
                    }
                })
                .connect(host, port)
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        failed("cannot connect to " + address() + ": " + f.cause());
                    }
                });
    }

    /** Any thread. */
    void close() {
        Channel c = channel;
        if (c != null) {
            c.close();
        }
    }

    /** Command thread: the image is loaded; the stream may follow. */
    void startStream() {
        channel.eventLoop().execute(() -> {
            if (state == State.LOADING) {
                state = State.STREAM;
                channel.config().setAutoRead(true);
                process();
            }
        });
    }

    long lastReadMillis() {
        return lastReadMillis;
    }

    /** Command thread: the offset now applied, for the next acknowledgement. */
    void appliedOffset(long offset) {
        appliedOffset = offset;
    }

    /** Any thread: tells the primary the offset applied (every second, and when it asks). */
    void acknowledge() {
        Channel c = channel;
        if (c != null && state == State.STREAM) {
            ByteBuf out = Unpooled.buffer(64);
            RespWriter.command(out, words("REPLCONF", "ACK", Long.toString(appliedOffset)));
            c.writeAndFlush(out);
        }
    }

    /** Command thread: records of this many bytes were applied. */
    void applied(long bytes) {
        if (inFlight.addAndGet(-bytes) < LOW_BYTES && paused) {
            channel.eventLoop().execute(() -> {
                if (paused && state == State.STREAM) {
                    paused = false;
                    channel.config().setAutoRead(true);
                }
            });
        }
    }

    private final class Handler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            acc = ctx.alloc().buffer(64 * 1024);
            lastReadMillis = System.currentTimeMillis();
            acks = ctx.channel().eventLoop().scheduleAtFixedRate(PrimaryLink.this::acknowledge, 1, 1, TimeUnit.SECONDS);
            nextStep();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            lastReadMillis = System.currentTimeMillis();
            ByteBuf in = (ByteBuf) msg;
            try {
                if (state != State.CLOSED) {
                    acc.writeBytes(in);
                }
            } finally {
                in.release();
            }
            process();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            failed("the primary closed the connection");
            if (acc != null) {
                acc.release();
                acc = null;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failed("the link failed: " + cause);
        }
    }

    private void nextStep() {
        awaiting = steps.poll();
        ByteBuf out = Unpooled.buffer();
        RespWriter.command(out, awaiting);
        channel.writeAndFlush(out);
    }

    private void process() {
        try {
            while (acc != null && state != State.CLOSED) {
                switch (state) {
                    case HANDSHAKE: {
                        String line = readLine();
                        if (line == null) {
                            return;
                        }
                        String step = new String(awaiting[0], StandardCharsets.US_ASCII);
                        if (line.startsWith("-")) {
                            failed("the primary refused " + step + ": " + line.substring(1));
                            return;
                        }
                        if (!step.equals("PSYNC")) {
                            nextStep();
                            continue;
                        }
                        String[] w = line.split(" ");
                        if (w[0].equals("+CONTINUE") && w.length >= 2) {
                            state = State.STREAM;           // what follows is the stream, from where it was
                            String id = w[1];
                            engine.submit((Runnable) () -> replication.continued(this, id));
                            continue;
                        }
                        if (!w[0].equals("+FULLRESYNC") || w.length < 3) {
                            failed("unexpected reply to PSYNC: " + line);
                            return;
                        }
                        replid = w[1];
                        offset = Long.parseLong(w[2]);
                        primaryEpoch = w.length >= 4 ? Long.parseLong(w[3]) : 0;
                        state = State.IMAGE_HEADER;
                        continue;
                    }
                    case IMAGE_HEADER: {
                        String line = readLine();
                        if (line == null) {
                            return;
                        }
                        if (!line.startsWith("$EOF:") || line.length() != 5 + MARK) {
                            failed("unexpected image header: " + line);
                            return;
                        }
                        endMark = new EndMark(line.substring(5).getBytes(StandardCharsets.US_ASCII));
                        filePath = Files.createTempFile(dir, "sync-", ".jrdb.tmp");
                        file = FileChannel.open(filePath, StandardOpenOption.WRITE);
                        state = State.IMAGE;
                        continue;
                    }
                    case IMAGE: {
                        if (!endMark.scan(acc, this::writeImage)) {
                            acc.discardReadBytes();
                            return;
                        }
                        file.force(true);
                        file.close();
                        file = null;
                        state = State.LOADING;
                        channel.config().setAutoRead(false);
                        Path image = filePath;
                        String id = replid;
                        long from = offset;
                        long ep = primaryEpoch;
                        engine.submit((Runnable) () -> replication.imageReceived(this, image, id, from, ep));
                        return;
                    }
                    case STREAM: {
                        List<Record> batch = new ArrayList<>();
                        long total = 0;
                        while (true) {
                            int before = acc.readerIndex();
                            byte[][] argv = parser.parse(acc);
                            recordBytes += acc.readerIndex() - before;
                            if (argv == null) {
                                break;
                            }
                            batch.add(new Record(argv, recordBytes));
                            total += recordBytes;
                            recordBytes = 0;
                        }
                        acc.discardReadBytes();
                        if (!batch.isEmpty()) {
                            if (inFlight.addAndGet(total) > HIGH_BYTES && !paused) {
                                paused = true;
                                channel.config().setAutoRead(false);
                            }
                            engine.submit((Runnable) () -> replication.apply(this, batch));
                        }
                        return;
                    }
                    default:
                        return;
                }
            }
        } catch (IOException | RespProtocolException | NumberFormatException e) {
            failed("the link failed: " + e);
        }
    }

    private String readLine() {
        int end = acc.indexOf(acc.readerIndex(), acc.writerIndex(), (byte) '\n');
        if (end < 0) {
            if (acc.readableBytes() > MAX_LINE) {
                failed("a reply line longer than " + MAX_LINE + " bytes");
            }
            return null;
        }
        int len = end - acc.readerIndex();
        String line = acc.toString(acc.readerIndex(), len, StandardCharsets.UTF_8);
        acc.readerIndex(end + 1);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private void writeImage(ByteBuf from, int n) throws IOException {
        while (n > 0) {
            n -= from.readBytes(file, n);
        }
    }

    /** I/O thread (or a connect listener): this attempt is over; the command thread decides what next. */
    private void failed(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        if (acks != null) {
            acks.cancel(false);
        }
        if (channel != null) {
            channel.close();
        }
        if (file != null) {                      // an image half received: not handed over, so ours to delete
            try {
                file.close();
            } catch (IOException ignored) {
                // failing anyway
            }
            file = null;
            Path p = filePath;
            engine.submit((Runnable) () -> replication.discardImage(p));
        }
        engine.submit((Runnable) () -> replication.linkFailed(this, reason));
    }
}
