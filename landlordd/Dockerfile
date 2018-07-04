FROM landlord/landlord:0.1.0
COPY test/target/scala-2.12/classes /classes
CMD ["-cp", "/classes", "-Dgreeting=Welcome", "example.Hello", "ArgOne", "ArgTwo"]
