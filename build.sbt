name := "bokun-link-service"
version := "1.0"
scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)

libraryDependencies ++= Seq(
  guice,
  javaJdbc,
  evolutions,
  "mysql" % "mysql-connector-java" % "8.0.33",
  "com.zaxxer" % "HikariCP" % "5.1.0",
  "redis.clients" % "jedis" % "5.1.0",
  "software.amazon.awssdk" % "sqs" % "2.25.11",
  "org.jsoup" % "jsoup" % "1.17.2",
  // Test-scope only, none of this is part of the deployed application.
  "junit" % "junit" % "4.13.2" % Test,
  "com.novocode" % "junit-interface" % "0.11" % Test,
  "org.mockito" % "mockito-core" % "5.11.0" % Test
)

// Runs tests in their own forked JVM, isolated from the sbt process itself.
Test / fork := true