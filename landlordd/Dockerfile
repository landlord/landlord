FROM landlord/landlord
COPY test/target/scala-2.12/classes /classes
CMD ["-cp", "/classes", "-Dgreeting=Welcome", "example.Hello", "ArgOne", "ArgTwo"]
