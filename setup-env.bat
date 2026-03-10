#!/usr/bin/env bash
set -e

echo "==== Setup JDK 21 + Gradle ===="

sudo apt-get update -qq
sudo apt-get install -y openjdk-21-jdk gradle

# -------------------------
# Environment variables
# -------------------------
JAVA_HOME_PATH=$(dirname $(dirname $(readlink -f $(which java))))

grep -qxF "export JAVA_HOME=$JAVA_HOME_PATH" ~/.bashrc \
  || echo "export JAVA_HOME=$JAVA_HOME_PATH" >> ~/.bashrc

grep -qxF 'export PATH=$JAVA_HOME/bin:$PATH' ~/.bashrc \
  || echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc

export JAVA_HOME=$JAVA_HOME_PATH
export PATH=$JAVA_HOME/bin:$PATH

# -------------------------
# Verify
# -------------------------
echo ""
echo "==== Installed versions ===="
java -version
gradle -v

echo ""
echo "Done. Run: source ~/.bashrc"