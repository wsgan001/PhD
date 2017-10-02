#!/bin/bash

N=3
$SPARK_HOME/sbin/stop-all.sh
truncate -s 0 $SPARK_HOME/conf/slaves
echo "acald013@dblab-rack15" >> $SPARK_HOME/conf/slaves
$SPARK_HOME/sbin/start-all.sh

for i in `seq 7 -1 1`
do
	for j in `seq 1 $N`
	do
		./runBerlin.sh $i 1024 50
	done
done
$SPARK_HOME/sbin/stop-all.sh
echo "Done!!!"