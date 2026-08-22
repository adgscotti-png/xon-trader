FROM eclipse-temurin:21-jdk-noble

# Strumenti base per cmdline-tools e build Android
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip zip curl git ca-certificates file procps \
 && rm -rf /var/lib/apt/lists/*

# Gradle 8.9 (pin: coppia stabile con AGP 8.7.3)
ARG GRADLE_VERSION=8.9
RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && unzip -q /tmp/gradle.zip -d /opt \
 && ln -sfn "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
 && rm /tmp/gradle.zip
ENV GRADLE_HOME=/opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"

# L'SDK Android NON sta nell'immagine: arriva dal volume montato su /opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

WORKDIR /work/repo
