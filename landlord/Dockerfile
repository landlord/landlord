FROM alpine:3.5
WORKDIR /opt/docker
COPY target/x86_64-unknown-linux-musl/release/landlord /usr/local/bin/
RUN mkdir -p /var/run/landlord
RUN chown daemon /var/run/landlord
USER daemon