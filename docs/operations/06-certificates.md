# Certificates: the backend's own CA

How the site's TLS certificate is made, installed, built into the app, renewed and checked. Every
command is here, for a fresh RHEL 9 machine with no network and for whoever builds the client.
Why it is done this way is
[D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name);
which port serves it is [05](05-ports-and-firewall.md).

## 1. What is certified, and why with a CA of our own

- **The site has one public address, an IP, and no domain.** A public certificate authority
  certifies names it can check in DNS, and the site has none. So the backend has a CA of its own:
  a root, made once, that signs the site's certificate.
- **One certificate serves the whole site.** It names:
  - the site's public address, which players dial on 443 (the API, the lobby);
  - every arena's name, which a client sends on 9001 (`a1.arena.internal` and the rest, 05 §3).

  nginx serves it on both ports, and ends TLS there. The same certificate is installed on all
  three machines, since any of them may be the front.
- **The app ships with the root's certificate and trusts it alone** (`TrustRoot` in the client's
  core). A certificate is accepted when its chain ends at that root, it is valid, and it names the
  address dialled or the arena's name. Nothing is pinned, so the site's certificate is renewed
  every year without a client release. Only a new root needs one.
- **MySQL's TLS is apart**: its own CA, for the private network (01 §9). Do not mix the two.

| File | What | Where it lives | Secret |
|---|---|---|---|
| `ca.key` | the root's key | **off every server**: the operator's machine, and a copy apart | yes, the most important one |
| `ca.pem` | the root's certificate | the app; every machine's `/etc/backend/tls/ca.pem`; anywhere | no |
| `privkey.pem` | the site certificate's key | every machine's `/etc/backend/credentials/privkey.pem`, mode 0600 | yes |
| `fullchain.pem` | the site's certificate | every machine's `/etc/backend/tls/fullchain.pem` | no |

`scripts/private-ca.sh` makes all four with the system's `openssl`, which every RHEL 9 machine has:
EC P-256 keys, SHA-256 signatures. It runs from the repository (`backend/scripts/`) or from an
installed release (`/opt/backend/scripts/`).

## 2. Making the root, once

On a machine kept apart from the servers, an operator's workstation for example:

```bash
backend/scripts/private-ca.sh root ~/backend-ca
```

It prints the root's subject, its end, ten years from now (`ROOT_DAYS` changes it), and its
SHA-256 fingerprint. Write the fingerprint down: it is how anyone later tells this root from
another.

- **Keep `~/backend-ca/ca.key` secret, with a copy apart**, an encrypted disk or a password
  manager's file vault, for example. Whoever holds it can pass for the backend to every player (§8).
- **It refuses to run twice into one directory**: a root replaced by mistake would leave every
  installed app trusting the old one.
- On one machine alone, the install guide makes the root on the server itself, in
  `/root/backend-ca`, and says to move the key off afterwards ([03 §9](03-install-guide.md)).

## 3. Issuing the site's certificate

With the root, for the site's address and every arena's name. The names come from nginx's map,
so the certificate and the routing cannot disagree:

```bash
backend/scripts/private-ca.sh issue ~/backend-ca ~/backend-certificate 203.0.113.10 \
    $(backend/scripts/private-ca.sh names backend/deploy/nginx/backend-stream.conf)
backend/scripts/private-ca.sh show ~/backend-certificate/fullchain.pem
```

- Put the site's public address in place of `203.0.113.10`.
- Give `names` the map the machines use: the release's `nginx-conf/backend-stream.conf`, or a
  machine's `/etc/backend/nginx/backend-stream.conf`.
- `show` lists what it names: `IP Address:203.0.113.10, DNS:a1.arena.internal, …`.
- The certificate is valid 398 days (`DAYS` changes it). It is checked against the root before the
  script prints that it is done.
- It refuses an output directory that already holds a key or a certificate: each issue goes into
  a new one, `~/backend-certificate-2027` say.

## 4. Installing it, on every machine

Copy three files to each machine over the internal network, then install them there as root:

```bash
scp ~/backend-ca/ca.pem ~/backend-certificate/fullchain.pem ~/backend-certificate/privkey.pem root@10.0.0.1:/root/
```

On the machine:

```bash
install -m 0644 /root/ca.pem /etc/backend/tls/ca.pem
/opt/backend/scripts/install-certificate.sh /root/fullchain.pem /root/privkey.pem
/opt/backend/scripts/install-certificate.sh --check
rm /root/fullchain.pem /root/privkey.pem
```

`install-certificate.sh` installs nothing unless:
- the key is the certificate's;
- the chain reaches the root in `/etc/backend/tls/ca.pem`, which it trusts alone when that file
  is there (`CA_FILE` overrides);
- the certificate is valid now, with a warning inside 14 days;
- it names everything the machine serves: nginx's `server_name`, the site's address, and every
  name in the stream's map.

It then puts the certificate in `/etc/backend/tls/fullchain.pem` and the key in
`/etc/backend/credentials/privkey.pem` (0600). It checks them with `nginx -t`, putting the
previous ones back if nginx refuses them. It reloads nginx, which drops no connection, and
checks what nginx then serves on 443 and on 9001. Before nginx has ever started, it installs the
files and says nginx serves them when started.

`--check` connects to each port as a device would, for every name, and fails on anything wrong
or within 14 days:

```
nginx 443: 203.0.113.10, expires 2027-11-21T06:38:30Z (397 days), 0 (ok)
nginx 9001: a1.arena.internal, expires 2027-11-21T06:38:30Z (397 days), 0 (ok)
...
```

## 5. In the app

For whoever builds the client:

- **The root**: `ca.pem` goes into the Unity project as a text asset, and into the
  `Trusted Root` field of `BackendClient` (`client/Unity/com.backend.client/Runtime/BackendClient.cs`).
  The core trusts it alone for the API, the lobby and matches: `TrustRoot.FromPem`.
- **The endpoints**: `https://203.0.113.10` for the API and `wss://203.0.113.10/lobby` for the lobby,
  the site's address.
- **Matches need nothing more**: the grant carries the address to dial and the name to give
  (`tlsName`), and the core checks the certificate against that name.
- **Nothing to pin, and no plaintext fallback**: a certificate that does not check is a connection
  refused, and the client says so.

The headless driver does the same with `--trust ca.pem` ([client/Headless/README](../../client/Headless/README.md)).

**Not proved here**: the app in Unity on a phone. The core's trust is tested in `Core.Tests`
(`TrustRootTests`), and the Unity scripts compile against stubs (`client/unity-check.sh`). Whether
Unity's own runtime honours the core's certificate checks, on Android and iOS, is to be seen on
devices: nothing here runs Unity.

## 6. Renewing, every year

A certificate lasts 398 days. Renew it a month before its end:
1. Issue a new one from the root (§3), into a new directory.
2. Install it on every machine (§4).

Nothing else changes. Players are not interrupted: nginx reloads without dropping a connection, and
the app trusts the root, not the certificate.

**Know when.** `install-certificate.sh --check` prints the days left. The gateway also reports the
certificate's end as `backend_gateway_edge_certificate_expiry_timestamp_seconds` (01 §7): alert
under 30 days. The runbook's routine lists the renewal ([02 §6](02-runbook.md#6-routine)).

## 7. Adding an arena, or changing the address

- **An arena added**, `c3` say:
  1. its line in `backend-stream.conf`'s map, on every machine (05 §3);
  2. a certificate issued with the new name (§3: `names` reads the map) and installed (§4);
  3. then the arena started.

  Until its name is in the certificate, the client refuses the arena's matches.
- **A new public address**:
  1. `server_name` in `backend.conf` and every arena's `ADVERTISE_HOST`;
  2. a certificate issued for the new address, and installed;
  3. the app's endpoints, a client release.

## 8. If the root's key leaks, or is lost

- **Leaked**: anyone holding it can stand in for the site and read what players send. Make a new
  root (§2), issue and install a certificate from it (§3, §4), and release the app with the new
  `ca.pem`. Apps with the old root cannot connect from then on, so they must update.
- **Lost**: the certificate installed keeps working until it ends. Before then, a new root, a
  certificate from it, and a client release, as above.

## 9. Testing and development

- **The scripts' own check**: `backend/scripts/check-certificates.sh`, on any machine with
  openssl, in a few seconds. It makes a root and certificates as §2 and §3 do and installs them as
  §4 does, nginx stood in for, and checks each refusal this page names: the name or the address
  missing, another root, another key, the system's roots without `ca.pem`, a root made twice, and
  the previous files put back when nginx refuses the new
  ([scripts README](../../backend/scripts/README.md#check-certificatessh)).
- **The end-to-end check**: `backend/scripts/check-ports-el9.sh` makes a root and a certificate with
  `private-ca.sh` and installs it with `install-certificate.sh`. It then runs the C# client
  through nginx on 443 and 9001, trusting that root alone, against arenas on two machines
  ([05 §9](05-ports-and-firewall.md#9-what-was-checked-and-what-was-not)).
- **The drills** (`client/headless-drill.sh`, `TLS=1`) give the arena a certificate of their own
  and connect to it directly, without nginx: that tests the arena's own TLS, which the site does
  not use.
- **On a developer's machine**, `private-ca.sh root` and `issue` make a test CA in any directory,
  for `127.0.0.1` and the arena's name.

## 10. When something goes wrong

| What you see | Why, and what to do |
|---|---|
| The client: the remote certificate is invalid, its chain untrusted | the app's root is not the one that issued nginx's certificate: compare `private-ca.sh show` of both, issuer and fingerprint |
| The client: the name does not match | the certificate lacks the address dialled or the arena's name: `private-ca.sh show`, then issue again with `names` (§3) |
| A match connection closed at once, `stream.log` with the name and no upstream | the name is not in `backend-stream.conf`'s map on the front (05 §3) |
| `install-certificate.sh`: `does not verify` | `/etc/backend/tls/ca.pem` is not the root that signed it |
| `install-certificate.sh`: `does not name …` | issued before a name was added to the map or the address changed: issue again |
| Expired | renew (§6); the alert should have come 30 days before |
| By hand, what 443 serves | `openssl s_client -connect 203.0.113.10:443 -verify_ip 203.0.113.10 -CAfile ca.pem </dev/null` |
| By hand, what 9001 serves for an arena | `openssl s_client -connect 203.0.113.10:9001 -servername a1.arena.internal -verify_hostname a1.arena.internal -CAfile ca.pem </dev/null` |

Each ends with `Verify return code: 0 (ok)` when the certificate is right, with the certificate's
`subject=` line naming `backend` above it (`CN = backend` or `CN=backend`, by OpenSSL's version). Look for both: on a port that speaks no TLS, s_client gets no certificate (`no peer
certificate available`) and still prints `Verify return code: 0 (ok)`.
