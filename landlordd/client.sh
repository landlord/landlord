#!/bin/sh
trap 'cat <<< "hi"' SIGTERM SIGINT
( \
  echo "-cp classes example.Hello" && \
  tar -c -C /Users/huntc/Projects/farmco/landlord/landlordd/test/target/scala-2.12 classes &&
  while read x ; do echo $x ; done && \
  printf '\04' \
  ) | nc 127.0.0.1 9000 &
wait