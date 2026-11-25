# Certificates: the backend's own CA

This page says how the site's TLS certificate is made, installed, built into the app, renewed and checked. It is for
the operator who looks after the machines and for whoever builds the client. Every command is here, for a fresh RHEL 9
machine with no network. The reasons for doing it this way are in
[D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name);
which ports serve the certificate is [05](05-ports-and-firewall.md)'s. The words front, arena, TLS name and the
backend's root are defined in the [glossary](../glossary.md).

## 1. What is certified, and why with a CA of our own

- **The site has one public address, an IP, and no domain.** A public certificate authority (CA) only certifies names
  it can check in DNS, and the site has none. So the backend has a CA of its own: a root, made once, that signs the
  site's certificate.
- **One certificate serves the whole site.** It names:
  - the site's public address, which players dial on 443 (the API and the lobby);
  - every arena's name, which a client sends on 9001 (`a1.arena.internal` and the others, 05 §3).

  nginx serves it on both ports, and ends TLS there. The same certificate is installed on all three machines, since
  any of them may be the front. Port 80, the way in clear (D-115), uses no certificate at all.
- **The app ships with the root's certificate, and trusts it alone** (`TrustRoot` in the client's core). The app
  accepts a certificate when that root issued it (its signature checks with the root's key, §5), it is valid now,
  and it names the address dialled or the arena's name. Nothing is pinned, so the site's certificate can be renewed
  every year without a new release of the app. Only a new root needs a new release.
- **MySQL's TLS is separate**: it has its own CA, for the private network (01 §9). Do not mix the two.

| File | What | Where it lives | Secret |
|---|---|---|---|
| `ca.key` | the root's key | **off every server**: the operator's machine, with a copy kept apart | yes, the most important one |
| `ca.pem` | the root's certificate | the app; every machine's `/etc/backend/tls/ca.pem`; anywhere | no |
| `privkey.pem` | the site certificate's key | every machine's `/etc/backend/credentials/privkey.pem`, mode 0600 | yes |
| `fullchain.pem` | the site's certificate | every machine's `/etc/backend/tls/fullchain.pem` | no |

`scripts/private-ca.sh` makes all four files with the system's `openssl`, version 1.1.1 or later, which every RHEL 8
and RHEL 9 machine has (D-103). It runs from the repository (`backend/scripts/`) or from an installed release
(`/opt/backend/scripts/`). Its keys are RSA: 3072 bits for the root and 2048 for a certificate, with SHA-256
signatures ([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)).
RSA is the one kind of key Unity 2019's runtime can check: it cannot read an EC key at all. The TLS key exchange is
still ECDHE, and the ciphers are as they were. **A root made before 2026-11-05 is EC**: make the root again (§2) and
every certificate from it (§3), and ship the new `ca.pem` in the app (§5).

## 2. Making the root, once

Make the root on a machine kept apart from the servers, an operator's workstation for example:

```bash
backend/scripts/private-ca.sh root ~/backend-ca
```

It prints the root's subject, its end date, ten years from now (`ROOT_DAYS` changes it), and its SHA-256
fingerprint. Write the fingerprint down: it is how anyone can later tell this root from another.

- **Keep `~/backend-ca/ca.key` secret, with a copy in another place**: an encrypted disk or a password manager's file
  vault, for example. Whoever holds it can pretend to be the backend to every player (§8).
- **It refuses to run twice into one directory**: a root replaced by mistake would leave every installed app trusting
  the old one.
- On the launch's one machine, the install guide makes the root on the server itself, in `/root/backend-ca`, and says
  to move the key off afterwards ([03 §9](03-install-guide.md#9-nginx-and-the-certificate)).

## 3. Issuing the site's certificate

Issue the certificate with the root, for the site's address and every arena's name. The names come from nginx's list
of arenas, `backend-arenas.conf`, which both of nginx's maps read (9001's and 80's). So the certificate and the routing
cannot disagree:

```bash
backend/scripts/private-ca.sh issue ~/backend-ca ~/backend-certificate 203.0.113.10 \
    $(backend/scripts/private-ca.sh names backend/deploy/nginx/backend-arenas.conf)
backend/scripts/private-ca.sh show ~/backend-certificate/fullchain.pem
```

- Put the site's public address in place of `203.0.113.10`.
- Give `names` the list the machines use: the release's `nginx-conf/backend-arenas.conf`, or a machine's
  `/etc/backend/nginx/backend-arenas.conf` (D-115; before it, the names were in `backend-stream.conf`'s map).
- `show` lists what the certificate names: `IP Address:203.0.113.10, DNS:a1.arena.internal, …`.
- The certificate is valid for 398 days (`DAYS` changes it), and never past its root's end: a certificate that would
  outlive the root is refused (O-73), because a device refuses the chain once the root has expired. Near the root's
  end, a new root means a new release of the app. The script checks the certificate against the root before it says
  that it is done.
- It refuses an output directory that already holds a key or a certificate. Each issue goes into a new directory,
  `~/backend-certificate-2027` for example.

## 4. Installing it, on every machine

1. Copy three files to each machine over the internal network:

   ```bash
   scp ~/backend-ca/ca.pem ~/backend-certificate/fullchain.pem ~/backend-certificate/privkey.pem root@10.0.0.1:/root/
   ```

2. On the machine, as root, install them, check what nginx now serves, and remove the copies:

   ```bash
   install -m 0644 /root/ca.pem /etc/backend/tls/ca.pem
   /opt/backend/scripts/install-certificate.sh /root/fullchain.pem /root/privkey.pem
   /opt/backend/scripts/install-certificate.sh --check
   rm /root/fullchain.pem /root/privkey.pem
   ```

**What `install-certificate.sh` checks.** It installs nothing unless all of these hold:
- the key belongs to the certificate;
- the chain reaches the root in `/etc/backend/tls/ca.pem`. When that file is there, the script trusts that root
  alone (`CA_FILE` names another);
- the certificate is valid now. It warns when fewer than 14 days are left, of the certificate or of its root (O-73;
  `WARN_DAYS` changes the 14);
- it names everything the machine serves, by a DNS name or an address in its subjectAltName (SAN), never by the CN
  alone (O-74). That is: nginx's `server_name`, the site's address, which must be there when the list names arenas
  (O-77); every name in nginx's list of arenas, `backend-arenas.conf` (`ARENAS_CONF` names another file); and the
  `ADVERTISE_HOST` of each arena that ends TLS itself, without the front.

**What it then does:**
1. It writes the certificates the file holds, and nothing else from it (not a key given in the same file, O-75), to
   `/etc/backend/tls/fullchain.pem`, and the key to `/etc/backend/credentials/privkey.pem` (0600). It writes both
   before it replaces either file.
2. It tests them with `nginx -t`. If nginx refuses them, it puts the previous files back, or leaves none where there
   were none. Once nginx accepts them, the replaced key and certificate are not kept (O-76).
3. It reloads nginx, which drops no connection.
4. It checks what nginx then serves, on 443 and on 9001.

Before nginx has ever started, the script installs the files and says that nginx will serve them when it starts. It
first makes nginx's log directory for `nginx -t` (O-49), as the unit does at its first start.

`--check` connects to each port as a device would, for every name, and fails on anything wrong, or on fewer than 14
days left:

```
nginx 443: 203.0.113.10, expires 2027-11-21T06:38:30Z (397 days), 0 (ok)
nginx 9001: a1.arena.internal, expires 2027-11-21T06:38:30Z (397 days), 0 (ok)
...
```

## 5. In the app

For whoever builds the client:

- **The root**: `ca.pem` goes into the Unity project renamed `ca.pem.txt`, since Unity imports a `.txt` file as a
  text asset and not a `.pem` one, and into the `Trusted Root` field of
  `BackendClient` (`client/Unity/com.backend.client/Runtime/BackendClient.cs`). The core trusts it alone, for the
  API, the lobby and matches: `TrustRoot.FromPem`.
- **The endpoints**, in `BackendClient`'s `Api Endpoints` and `Lobby Url` fields: `https://203.0.113.10` for the API
  and `wss://203.0.113.10/lobby` for the lobby, with the site's address.
- **Matches need nothing more**: the grant carries the address to dial and the name to give (`tlsName`), and the core
  checks the certificate against that name.
- **The check is the core's own**, the same on every runtime
  ([D-108](../architecture/03-decision-log.md#d-108--the-client-for-unity-20194-and-net-framework-c-73-a-certificate-check-of-its-own-an-rsa-root)).
  The certificate must be issued by the root (its RSA signature, over SHA-256, verified with the root's key, which
  nothing else can sign for), valid now, and must name the host or the `tlsName` in its subjectAltName. The runtime's
  own verdict is not asked. A root that is not RSA is refused when the app starts, and the app says so.
- **Nothing to pin, and no fallback to clear**: in a build given `https://` and `wss://`, a certificate that does not
  check is a refused connection, and the client says so. A build given `http://` and `ws://` uses no certificate and
  no root at all; any build may be set up that way (D-115, [11 §5](11-phone-to-backend-routes.md#5-on-a-development-machine)).

The headless driver does the same with `--trust ca.pem` ([client/Headless/README](../../client/Headless/README.md)).

**Not proved here**: the app in Unity on a phone. The core's trust is tested in `Core.Tests` (`TrustRootTests`) on
.NET 8, and on Unity 2019.4.28f1's own Mono by `client/unity2019-check.sh`: certificates of `private-ca.sh` accepted
and refused, and the API, the lobby and a match each through TLS. That TLS is Mono's own, outside the editor, not
Unity's (the editor's and the player's). So whether Unity's TLS on Android (C-10) completes the handshake is still to
be seen, in the editor and on devices; the check it then calls is the same code.

## 6. Renewing, every year

A certificate lasts 398 days. Renew it a month before its end:
1. Issue a new one from the root (§3), into a new directory.
2. Install it on every machine (§4).

Nothing else changes. Players are not interrupted: nginx reloads without dropping a connection, and the app trusts
the root, not the certificate.

**Know when it is due.** `install-certificate.sh --check` prints the days left, and fails with fewer than 14. The
gateway reports the certificate's end as the metric `backend_gateway_edge_certificate_expiry_timestamp_seconds`
(01 §11), and the watch raises the alert `CertificateExpiring` within 14 days of it
([08 §3](08-monitoring.md#3-the-alerts)). By then the renewal is two weeks late, so renew on the routine's date, not
on the alert ([02 §6](02-runbook.md#6-routine)).

## 7. Adding an arena, or changing the address

- **An arena added**, `c3` for example, in this order:
  1. its line in `backend-arenas.conf`, on every machine (05 §3);
  2. a certificate issued with the new name (§3: `names` reads the list), and installed (§4);
  3. then the arena started.

  Until its name is in the certificate, a client with TLS refuses the arena's matches.
- **A new public address**:
  1. `server_name` in `backend.conf`, and every arena's `ADVERTISE_HOST`;
  2. a certificate issued for the new address, and installed;
  3. the app's endpoints, in a new release of the app.

## 8. If the root's key leaks, or is lost

- **Leaked**: anyone holding the key can stand in for the site and read what players send. Make a new root (§2),
  issue and install a certificate from it (§3, §4), and release the app with the new `ca.pem`. Apps with the old root
  cannot connect from then on, so their players must update.
- **Lost**: the installed certificate keeps working until it ends. Before then you need a new root, a certificate
  from it, and a new release of the app, as above.

## 9. Testing and development

- **The scripts' own check**: `backend/scripts/check-certificates.sh` runs on any machine with openssl, in a few
  seconds ([scripts README](../../backend/scripts/README.md#check-certificatessh)). It makes a root and certificates
  as §2 and §3 do, and installs them as §4 does, with a stand-in for nginx. It checks each refusal this page names:
  - the name or the address missing; another root; another key; the system's roots used without `ca.pem`; a root
    made twice;
  - the previous files put back when nginx refuses the new ones, and nothing left behind by a first install it
    refuses;
  - a key inside the certificate's file, kept out of it; `server_name _`; a name in the CN alone; a certificate that
    would outlive its root;
  - an OpenSSL older than 1.1.1; a root in `SSL_CERT_DIR` kept out; and a refusal in openssl's own words.

  `IMAGE=<image>` runs it in a container image, on that image's openssl. RHEL 8's 1.1.1 and RHEL 9's 3.x are both
  checked (D-103).
- **The end-to-end check**: `backend/scripts/check-ports-el9.sh` makes a root and a certificate with `private-ca.sh`
  and installs it with `install-certificate.sh`. It then runs the C# client through nginx on 443 and 9001, trusting
  that root alone, against arenas on two machines
  ([05 §9](05-ports-and-firewall.md#9-what-was-checked-and-what-was-not)).
- **The drills** (`client/headless-drill.sh`): with `TLS=1` they give the arena a certificate of their own and
  connect to it directly, without nginx. That tests the arena's own TLS, which the site does not use. With
  `FRONT=https` or `FRONT=http` they go through the release's nginx instead, as `dev-nginx.sh` sets it up, with a root
  made for the drill (D-107).
- **On a developer's machine**, `private-ca.sh root` and `issue` make a test CA in any directory, for `127.0.0.1` and
  the arena's name. `dev-nginx.sh` does this in `work/nginx`, for the address clients dial, `127.0.0.1` and the
  arena's name. It keeps the root, and issues the certificate again when it no longer names those, is not the root's,
  or ends within a day; a root made before D-108, EC, is replaced with an RSA one
  ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)).

## 10. When something goes wrong

| What you see | Why, and what to do |
|---|---|
| The client: the remote certificate is invalid, its chain untrusted | the app's root is not the one that issued nginx's certificate: compare `private-ca.sh show` of both, issuer and fingerprint |
| The client: the name does not match | the certificate lacks the address dialled or the arena's name: `private-ca.sh show`, then issue again with `names` (§3) |
| A match connection closed at once, `stream.log` with the name and no upstream; or a 404 on 80's `/match/<name>` | the name is not in `backend-arenas.conf` on the front (05 §3) |
| `install-certificate.sh`: `does not verify` | `/etc/backend/tls/ca.pem` is not the root that signed it |
| `install-certificate.sh`: `does not name …` | the certificate was issued before a name was added to the list, or before the address changed: issue it again |
| Expired | renew (§6); `CertificateExpiring` should have fired 14 days before (08 §3) |
| By hand, what 443 serves | `openssl s_client -connect 203.0.113.10:443 -verify_ip 203.0.113.10 -CAfile ca.pem </dev/null` |
| By hand, what 9001 serves for an arena | `openssl s_client -connect 203.0.113.10:9001 -servername a1.arena.internal -verify_hostname a1.arena.internal -CAfile ca.pem </dev/null` |

When the certificate is right, each of the two commands ends with `Verify return code: 0 (ok)`, and above it the
certificate's `subject=` line names `backend` (`CN = backend` or `CN=backend`, depending on OpenSSL's version). Look
for both. On a port that speaks no TLS, `s_client` gets no certificate (`no peer certificate available`) and still
prints `Verify return code: 0 (ok)`.
