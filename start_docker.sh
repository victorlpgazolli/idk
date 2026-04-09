docker build -f Dockerfile.build --target runner -t idk-runner .

docker run -it \
    -v $(pwd):/app \
    --add-host=host.docker.internal:host-gateway \
    -e ANDROID_ADB_SERVER_ADDRESS=host.docker.internal \
    -e ANDROID_ADB_SERVER_PORT=5037 \
    -p 8080:8080 \
    idk-runner \
    /bin/sh
