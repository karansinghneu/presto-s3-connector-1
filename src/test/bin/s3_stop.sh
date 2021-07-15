#!/bin/bash

# 
#  Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
# 
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
# 
#      http://www.apache.org/licenses/LICENSE-2.0
# 
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# 
#  Note: This file contains changes from PrestoDb. Specifically the PrestoS3InputStream is inspired from the
#  Hive Connector.
#  https://github.com/prestodb/presto/blob/master/presto-hive/src/main/java/com/facebook/presto/hive/s3/PrestoS3FileSystem.java
# 

s3image=scality/s3server

for image in `docker ps | awk '{print $1}' | grep -v CONTAINER`
do
    if [ ! -z "`docker inspect $image | grep '"Image":' | grep $s3image`" ]
    then
        echo "Stop container $image" && docker stop $image
    fi
done

for image in `docker ps -a | awk '{print $1}' | grep -v CONTAINER`
do
    if [ ! -z "`docker inspect $image | grep '"Image":' | grep $s3image`" ]
    then
        echo "Remove container $image" && docker rm $image
    fi
done