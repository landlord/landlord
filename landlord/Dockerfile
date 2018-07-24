FROM alpine:3.7
COPY --chown=2:2 docker/ /
COPY --chown=daemon:daemon target/x86_64-unknown-linux-musl/release/landlord /usr/local/bin/
ENTRYPOINT ["/usr/local/bin/landlord"]
USER daemon
