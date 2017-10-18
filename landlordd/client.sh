#!/bin/sh
( \
  echo "-cp classes example.Hello" && \
  tar -c -C $(pwd)/test/target/scala-2.12 classes &&
  cat <&0 && \
  printf '\04' \
  ) | nc 127.0.0.1 9000
