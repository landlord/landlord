FROM alpine:3.7
WORKDIR /opt/docker
RUN apk add --no-cache shadow
COPY target/x86_64-unknown-linux-musl/release/landlord /usr/local/bin/
RUN \
  mkdir -p /var/run/landlord && \
  chown daemon /var/run/landlord && \
  usermod -d /var/run/landlord daemon
USER daemon
