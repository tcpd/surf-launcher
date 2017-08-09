#!/bin/bash -u -e
# source ~/.bashrc

# build muse jar
cd ../muse
mvn -f pom-jar.xml

# build epadd war
cd ../epadd
mvn # 

# build launcher
cd ../epadd-launcher
mvn # 

# build standalone
ant standalone-jar-no-discovery

