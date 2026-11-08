FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/droidtosh-relay .

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/droidtosh-relay /droidtosh-relay
# Holds the TLS certificate and key (CERT_FILE/KEY_FILE) and the optional STATE_FILE.
VOLUME /data
EXPOSE 7443
ENTRYPOINT ["/droidtosh-relay"]
