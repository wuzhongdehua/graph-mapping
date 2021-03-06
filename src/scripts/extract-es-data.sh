#!/usr/bin/env bash

MAIN_JAR=../graph-mapping-0.1-SNAPSHOT/lib/graph-mapping.jar
MAIN_CLASS=software.uncharted.graphing.export.ESIngestExport
BASE_LOCATION=/user/${USER}/graphs



DATASET=

while [ "$1" != "" ]; do
	case $1 in
		-d | --dataset )
			shift
			DATASET=$1
			;;
        -cluster )
            export CLUSTER=true
            ;;
	esac
	shift
done

if [ "${DATASET}" == "" ]; then
	echo No dataset specified
	exit
fi

pushd ${DATASET}



echo
echo Running in `pwd`
echo Starting at `date`


MAX_LEVEL=`hdfs dfs -ls ${BASE_LOCATION}/${DATASET}/layout | awk -F'_' '{print $2}' | sort -nr | head -n1`

echo MAX_LEVEL: ${MAX_LEVEL}

echo MAX_LEVEL: ${MAX_LEVEL} > export.log

TIMEA=$(date +%s)

echo Clearing HDFS output folder
hdfs dfs -rm -r -skipTrash ${BASE_LOCATION}/${DATASET}/esexport

TIMEB=$(date +%s)

if [ "${CLUSTER}" == "true" ]; then
    echo Deploying in cluster mode

    DEPLOY_MODE=cluster
else
    echo Deploying in client mode

    DEPLOY_MODE=client
fi

echo Starting export run

echo spark-submit \
    --class ${MAIN_CLASS} \
    --num-executors 4 \
    --executor-cores 4 \
    --executor-memory 10g \
    --master yarn \
    --deploy-mode ${DEPLOY_MODE} \
    ${MAIN_JAR} \
    -sourceLayout ${BASE_LOCATION}/${DATASET}/layout \
    -output ${BASE_LOCATION}/${DATASET}/esexport \
    -maxLevel ${MAX_LEVEL} >> export.log

spark-submit \
    --class ${MAIN_CLASS} \
    --num-executors 4 \
    --executor-cores 4 \
    --executor-memory 10g \
    --master yarn \
    --deploy-mode ${DEPLOY_MODE} \
    ${MAIN_JAR} \
    -sourceLayout ${BASE_LOCATION}/${DATASET}/layout \
    -output ${BASE_LOCATION}/${DATASET}/esexport \
    -maxLevel ${MAX_LEVEL} |& tee -a export.log

# Note: Took out -spark yarn-client.  Should be irrelevant, but noted just in case I'm wrong.

TIMEC=$(date +%s)

echo >> export.log
echo >> export.log
echo Elapsed time for export: $(( ${TIMEC} - ${TIMEB} )) seconds >> export.log

echo
echo
echo Done at `date`
echo Elapsed time for export: $(( ${TIMEC} - ${TIMEB} )) seconds

popd
