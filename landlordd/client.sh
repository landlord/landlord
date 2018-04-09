#!/bin/sh
( \
  printf "l" && \
  printf -- $"l-cp\0classes\0example.Hello\n" && \
  tar -c -C $(pwd)/test/target/scala-2.12 classes && \
  cat <&0 \
  ) | \
nc -U /var/run/landlord/landlordd.sock