Con el paquete actualizado (`com.quartzjobs.jobs.woocommerce`) y el TTL de 1380 min, así quedan tus 2 requests finales:

**1) `WooCommerceAuthJob` — cada 2 horas (obtiene y cachea el token):**
```json
curl --location 'http://localhost:8080/api/jobs' \
--header 'Content-Type: application/json' \
--data-raw '{
"jobName": "woocommerce-auth-prod",
"jobGroup": "SYNC",
"jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceAuthJob",
"cronExpression": "0 0 0/2 * * ?",
"data": {
"loginUrl": "http://100.83.15.65:18080/api/v1/auth/login",
"syncLoginUrl": "http://100.83.15.65:18080/api/v1/sync/login",
"skusUrl": "http://100.83.15.65:18080/api/v1/sync/get-skus",
"username": "perucaos@gmail.com",
"password": "cesar203",
"adminEmail": "admin@tuempresa.com",
"tokenKey": "woocommerce-prod",
"maxErrors": "5",
"logFilePath": "logs/woocommerce-auth-prod.txt"
}
}'
```

**2) `WooCommerceDataSyncJob` — cada 15 minutos (usa el token cacheado):**
```json
curl --location 'http://localhost:8080/api/jobs' \
--header 'Content-Type: application/json' \
--data-raw '{
"jobName": "woocommerce-datasync-prod",
"jobGroup": "SYNC",
"jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceDataSyncJob",
"cronExpression": "0 */15 * * * ?",
"data": {
"syncMongoUrl": "http://100.83.15.65:18080/api/v1/sync/syncMongo",
"sendWebUrl": "http://100.83.15.65:18080/api/v1/sync/sendWeb",
"sendWebExchangeRateUrl": "http://100.83.15.65:18080/api/v1/sync/sendWebExchangeRate",
"sendCleanCacheUrl": "http://100.83.15.65:18080/api/v1/sync/sendCleanCache",
"adminEmail": "admin@tuempresa.com",
"tokenKey": "woocommerce-prod",
"tokenTtlMinutes": "1380",
"maxErrors": "5",
"logFilePath": "logs/woocommerce-datasync-prod.txt"
}
}'
```
