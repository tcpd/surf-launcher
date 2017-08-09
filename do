#!/bin/bash -u -e
# source ~/.bashrc

# build flattened jar
mvn # build the launcher

# add the needed icons etc.
ant standalone-jar

# package into exe and dmg
ant release
