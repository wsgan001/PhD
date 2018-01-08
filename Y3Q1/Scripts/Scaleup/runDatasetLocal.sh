#!/bin/bash

DATA_PATH=$1
DATASET=$2
EPSILON=$3
MU=$4
CORES=$5
MASTER=$6

spark-submit --class MaximalFinderExpansion /home/acald013/PhD/Y3Q1/PFlock/target/scala-2.11/pflock_2.11-2.0.jar \
--path $DATA_PATH \
--dataset $DATASET \
--epsilon $EPSILON \
--mu $MU \
--master $MASTER \
--cores $CORES 

DATE=`date`
echo "Done!!! $DATE"