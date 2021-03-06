#!/bin/sh

# Delete old jar
if [ -e $HOME/.bds/bds.jar ] 
then
  rm -f $HOME/.bds/bds.jar
fi

# Make sure 'bin' dir exists
if [ ! -d bin ]; then
  mkdir bin
fi

# Build Jar file
echo Building JAR file
ant 

# Build go program
echo
echo Building bds wrapper: Compiling GO program
cd go/bds/
export GOPATH=`pwd`
go clean
go build 
go fmt

# Build binay (go executable + JAR file)
cat bds $HOME/.bds/bds.jar > bds.bin
mv bds.bin bds
chmod a+x bds

# Remove JAR file
rm $HOME/.bds/bds.jar

cd -
