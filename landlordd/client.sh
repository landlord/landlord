#!/bin/sh
( \
  printf "l" && \
  echo "-cp classes example.Hello" && \
  tar -c -C $(pwd)/test/target/scala-2.12 classes && \
  cat <&0 \
  ) | \
nc -U /var/run/landlord/landlordd.sock