#!/bin/bash

USER=`whoami`
BASE_LOCATION=/user/${USER}/graphs

# Determine the number of graph levels to use, by hierarchy level, by a trivial
# hard-coded amount, but determining dynamically the number of hierarchy levels
# needed.
#
# This function just prints its output; as a caller, to store this in a
# variable (say, for example, LEVELS), use the following syntax:
#
#     LEVELS=($(hardCodedLevels <dataset> 3 2))
#
# This will generate tiling level numbers for each hierarchy level, allocating
# 3 for the top level, and 2 for all lower levels.
# 
# The outer parentheses ensure that the results are stored in an array, rather
# than as a single long string.
function hardCodedLevels {
	DATASET=$1
	TOP_LEVEL=$2
	NEXT_LEVELS=$3

	for level in `hdfs dfs -ls ${BASE_LOCATION}/${DATASET}/layout | awk '{print $8}' | awk -F '/' '{print $NF}' | grep level_ | awk -F '_' '{print $2}' | sort`
	do
		if [ $level -eq 0 ]; then
			echo ${TOP_LEVEL}
		else
			echo ${NEXT_LEVELS}
		fi
	done
}

# Determine the number of graph levels to use, by hierarchy level, according
# to the statistics generated by the layout process.
#
# This function just prints its output; as a caller, to store this in a
# variable (say, for example, LEVELS), use the following syntax:
#
#     LEVELS=($(levelsFromStats <dataset>))
#
# The outer parentheses ensure that the results are stored in an array, rather
# than as a single long string.
function levelsFromStats {
	DATASET=$1
	hdfs dfs -get ${BASE_LOCATION}/${DATASET}/layout/stats/part-00000 layout-stats
	TILE_LEVELS=(`awk -F , '{print $2}' layout-stats | sed 's/min recommended zoom level: //g' | sort -g`)

	LAST=
	LAST_DIFF=0
	for N in "${TILE_LEVELS[@]}"; do
		if [ "" != "${LAST}" ]; then
			LAST_DIFF=$(( ${N} - ${LAST} ))
			echo ${LAST_DIFF}
		fi
		LAST=${N}
	done
	# Assume the deepest level requires as many levels as the second-deepest.
	echo ${LAST_DIFF}

	rm layout-stats
}

# Determine the maximum hierarchy level of our dataset
function getMaxLevel {
	DATASET=$1
	hdfs dfs -ls ${BASE_LOCATION}/${DATASET}/layout | awk '{print $8}' | awk -F / '{print $NF}' | grep level_ | awk -F'_' '{print $2}' | sort -nr | head -n1
}

# Determine the number of partitions in our dataset
function getPartitions {
	DATASET=$1
	hdfs dfs -ls ${BASE_LOCATION}/${DATASET}/layout/level_0 | wc -l
}

# Determine the number of executors to use for our dataset
function getExecutors {
	DATASET=$1
	PARTITIONS=$( getPartitions ${DATASET} )
	echo $(expr $(expr ${PARTITIONS} + 7) / 8)
}

# Convert from a list of level sizes to the level parameters needed by tiling
function getLevelConfig {
	SIZES=$*

	LEVEL=0
	for SIZE in ${SIZES[@]}; do
		echo -Dgraph.levels.${LEVEL}=${SIZE}
		LEVEL=$(expr ${LEVEL} + 1)
	done
}

function relativeToSource {
	IFS="/"
	echo "${BASE_LOCATION}/${*}"
}

# #######################################################################################
# Examples of how to use the above functions
# #######################################################################################
# echo Max level: $(getMaxLevel affinity-nd)
# echo Partitions: $(getPartitions affinity-nd)
# echo Executors: $(getExecutors affinity-nd)

# LEVELS=($(levelsFromStats affinity-nd))
# echo Levels from stats: `( IFS=","; echo "${LEVELS[*]}" )`

# LEVELS=($(hardCodedLevels affinity-nd 3 2))
# echo Levels from heuristic: `( IFS=","; echo "${LEVELS[*]}" )`

# LEVELS=($(hardCodedLevels affinity-nd 3 2))
# echo Level config parameters: $( getLevelConfig ${LEVELS[@]} )

echo -Dtiling.source=$( relativeToSource abc def ghi )
