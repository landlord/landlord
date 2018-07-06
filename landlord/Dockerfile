FROM scratch
COPY --chown=2 docker/ /
COPY --chown=root target/x86_64-unknown-linux-musl/release/landlord /usr/local/bin/
ENTRYPOINT ["/usr/local/bin/landlord"]
USER daemon
