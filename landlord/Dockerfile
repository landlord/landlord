FROM scratch
COPY target/x86_64-unknown-linux-musl/release/landlord /usr/local/bin/
COPY --chown=2 empty /var/run/landlord
ENTRYPOINT ["usr/local/bin/landlord"]
USER 2
