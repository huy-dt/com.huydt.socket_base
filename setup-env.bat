#!/usr/bin/env bash

set -e

echo "Installing JDK 21 and Gradle..."

# install dependencies
sudo apt-get update
sudo apt-get install -y wget unzip

# -------------------------
# Install JDK 21
# -------------------------
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/latest/download/OpenJDK21U-jdk_x64_linux_hotspot.tar.gz"

wget -O jdk21.tar.gz $JDK_URL
tar -xzf jdk21.tar.gz

JDK_DIR=$(ls -d jdk-21*)

sudo mv $JDK_DIR /opt/jdk21

echo "export JAVA_HOME=/opt/jdk21" >> ~/.bashrc
echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> ~/.bashrc

export JAVA_HOME=/opt/jdk21
export PATH=$JAVA_HOME/bin:$PATH

# -------------------------
# Install Gradle
# -------------------------
GRADLE_VERSION=8.7
wget https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip

unzip gradle-${GRADLE_VERSION}-bin.zip
sudo mv gradle-${GRADLE_VERSION} /opt/gradle

echo "export GRADLE_HOME=/opt/gradle" >> ~/.bashrc
echo "export PATH=\$GRADLE_HOME/bin:\$PATH" >> ~/.bashrc

export GRADLE_HOME=/opt/gradle
export PATH=$GRADLE_HOME/bin:$PATH

# cleanup
rm jdk21.tar.gz
rm gradle-${GRADLE_VERSION}-bin.zip

echo "-------------------------"
java -version
gradle -v
echo "Installation completed!"