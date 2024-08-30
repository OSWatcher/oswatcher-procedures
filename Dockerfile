# Use Ubuntu as the base image
FROM ubuntu:24.04

# Avoid prompts from apt
ENV DEBIAN_FRONTEND=noninteractive

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV MAVEN_HOME=/opt/maven
ENV PATH=${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}

# Install dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    git \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Verify Java version
RUN java -version

# Install the latest Maven
RUN MAVEN_VERSION=$(curl -s https://maven.apache.org/download.cgi | grep -oP 'Apache Maven \K[0-9.]+' | head -1) && \
    wget https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz -P /tmp && \
    tar xf /tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven && \
    rm /tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz

# Verify Maven version
RUN mvn -version

# Set the working directory
WORKDIR /app

# docker run -u 1000:1000 -ti -v .:/app neo_proc
