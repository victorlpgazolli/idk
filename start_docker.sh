#!/bin/bash

confirm_and_run() {
    echo -e "About to run: \"$@\""
    
    read -p "Do you want to continue? [y/N]: " confirmation
    
    case "$confirmation" in 
        [yY]|[yY][eE][sS] ) 
            echo "Executing..."
            "$@"
            ;;
        * ) 
            echo "Canceled."
            exit 1
            ;;
    esac
}

confirm_and_run docker build -f Dockerfile.build -t idk-runner .

confirm_and_run make compile_bridge_agent

confirm_and_run docker run -it \
    --security-opt seccomp=unconfined \
    -v $(pwd):/app \
    -v idk-gradle-cache:/cache/gradle \
    -v idk-konan-cache:/cache/konan \
    --add-host=host.docker.internal:host-gateway \
    -e ANDROID_ADB_SERVER_ADDRESS=host.docker.internal \
    -e ANDROID_ADB_SERVER_PORT=5037 \
    -p 8080:8080 \
    idk-runner \
    /bin/sh
