#!/bin/bash
set -e

if [ $# -eq 0 ]
  then
    echo
    echo "dependencies.sh: Install Gradle jars in local .m2 repository."
    echo
    echo "Usage: ./dependencies.sh <gradle-version>"
    exit 1
fi

if ! [ -d "target" ]; then
  mkdir target
fi

if ! [ -d "target/dependencies" ]; then
  mkdir target/dependencies
fi

echo "Install Maven dependencies for $1"

cp ./src/maven/*.pom ./target/dependencies

cd ./target/dependencies

if [ ! -f "./gradle-$1-bin.zip" ]; then
    echo "Downloading Gradle version $1..."
  
    wget "https://services.gradle.org/distributions/gradle-$1-bin.zip"  
fi

if ! [ -d "gradle-$1" ]; then
  unzip "./gradle-$1-bin.zip"
fi

sed -i "s/VERSION/$1/g" *.pom

# these must match the ones in the project pom file
mvn install:install-file "-Dfile=./gradle-$1/lib/gradle-persistent-cache-$1.jar" -DpomFile=./gradle-persistent-cache.pom
mvn install:install-file "-Dfile=./gradle-$1/lib/gradle-messaging-$1.jar" -DpomFile=./gradle-messaging.pom
mvn install:install-file "-Dfile=./gradle-$1/lib/gradle-base-services-$1.jar" -DpomFile=./gradle-base-services.pom
mvn install:install-file "-Dfile=./gradle-$1/lib/gradle-files-$1.jar" -DpomFile=./gradle-files.pom
mvn install:install-file "-Dfile=./gradle-$1/lib/gradle-base-annotations-$1.jar" -DpomFile=./gradle-base-annotations.pom


