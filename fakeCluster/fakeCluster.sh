#!/bin/sh

taskDir="$HOME/.bds/fakeClusterTasks/"
doneDir="$HOME/.bds/fakeClusterTasks/done/"

mkdir -p $doneDir

while true
do
	next=`ls $taskDir/task.* 2> /dev/null | head -n 1`

	if [ ! -z "$next" ]
	then
		echo "Executing task $next"
		( $next ; mv $next $doneDir )&
	else
		sleep 1
	fi

done
