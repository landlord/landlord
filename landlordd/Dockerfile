FROM landlord/landlord
USER daemon
COPY test/target/scala-2.12/classes /classes
ENTRYPOINT ["/usr/local/bin/landlord", "-cp", "/classes", "-Dgreeting=Welcome", "example.Hello", "ArgOne", "ArgTwo"]
