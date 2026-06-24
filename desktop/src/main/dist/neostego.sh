#!/bin/bash

# @app.name@ - Steganography utility to hide messages into cover files
# Copyright 2007-@time.year@ (c) @author.name@ (mailto:@author.mail@)

# -Dsun.java2d.xrender=true: the XRender pipeline keeps window resizing smooth; the default X11
# pipeline re-blits the whole window on every resize tick (noticeably laggy, especially on XWayland).
JAVA_OPTS=(-Xmx1024m -Dsun.java2d.xrender=true)

SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$( cd -P "$( dirname "$SOURCE" )" >/dev/null 2>&1 && pwd )"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
DIR="$( cd -P "$( dirname "$SOURCE" )" >/dev/null 2>&1 && pwd )"

# exec so the JVM replaces this shell (forwards signals and the exit code);
# "$@" preserves arguments containing spaces or other special characters.
exec java "${JAVA_OPTS[@]}" -jar "${DIR}/lib/neostego.jar" "$@"
