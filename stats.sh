#!/bin/sh

PROJECT="routing-server"
git log --pretty=format:"%ad%x09%an%x09%s" --date=format:'%Y-%m-%d %H:%M:%S' > $PROJECT.log
gitinspector -T -F htmlembedded > $PROJECT.html
