#!/bin/sh
( \
  echo "-cp classes example.Hello" && \
  tar -c -C test/target/scala-2.12 classes &&
  while read x ; do echo $x ; done && \
  printf '\04' \
  ) | nc 127.0.0.1 9000 &
wait
