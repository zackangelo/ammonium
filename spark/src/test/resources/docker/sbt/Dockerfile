FROM test_spark/base
MAINTAINER Alexandre Archambault <alexandre.archambault@gmail.com>

ENV SBT_VERSION 0.13.8

# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb https://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb && \
  rm sbt-$SBT_VERSION.deb && \
  apt-get update && \
  apt-get install sbt

VOLUME ["/project"]

WORKDIR /project

CMD sleep infinity
