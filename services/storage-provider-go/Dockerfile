FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /out/upspa-sp ./cmd/sp
FROM alpine:3.20
RUN adduser -D -H -u 10001 upspa
USER upspa
COPY --from=build /out/upspa-sp /usr/local/bin/upspa-sp
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/upspa-sp"]
