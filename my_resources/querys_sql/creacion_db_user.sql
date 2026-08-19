-- ---------------------------------------------------------------
-- ========================= Para PRE (Opcion 1)
-- ---------------------------------------------------------------
CREATE USER 'quartzjobs_pre'@'%' IDENTIFIED WITH sha256_password BY 'cesar203';
GRANT USAGE ON *.* TO 'quartzjobs_pre'@'%';
ALTER USER 'quartzjobs_pre'@'%' REQUIRE NONE WITH MAX_QUERIES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_USER_CONNECTIONS 0;
CREATE DATABASE IF NOT EXISTS `quartzjobs_pre`;
GRANT ALL PRIVILEGES ON `quartzjobs_pre`.* TO 'quartzjobs_pre'@'%';
FLUSH PRIVILEGES;

-- ---------------------------------------------------------------
-- ========================= Para PRE (Opcion 2)
-- ---------------------------------------------------------------
-- 1. Crear el usuario con su contraseña
CREATE USER 'quartzjobs_pre'@'%' IDENTIFIED BY 'TU_CONTRASEÑA_AQUI';
-- 2. Asignar límites de uso (opcional, por defecto ya son 0 / ilimitados)
GRANT USAGE ON *.* TO 'quartzjobs_pre'@'%'
  REQUIRE NONE
  WITH MAX_QUERIES_PER_HOUR 0
       MAX_CONNECTIONS_PER_HOUR 0
       MAX_UPDATES_PER_HOUR 0
       MAX_USER_CONNECTIONS 0;
-- 3. Crear la base de datos si no existe
CREATE DATABASE IF NOT EXISTS `quartzjobs_pre`;
-- 4. Otorgar todos los privilegios sobre la base de datos al usuario
GRANT ALL PRIVILEGES ON `quartzjobs_pre`.* TO 'quartzjobs_pre'@'%';
-- 5. Aplicar los cambios de privilegios
FLUSH PRIVILEGES;







-- ========================= Para PROD
CREATE USER 'quartzjobs_prod'@'%' IDENTIFIED WITH sha256_password BY 'TU_CONTRASEÑA_AQUI';
GRANT USAGE ON *.* TO 'quartzjobs_prod'@'%';
ALTER USER 'quartzjobs_prod'@'%' REQUIRE NONE WITH MAX_QUERIES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_USER_CONNECTIONS 0;
CREATE DATABASE IF NOT EXISTS `quartzjobs_prod`;
GRANT ALL PRIVILEGES ON `quartzjobs_prod`.* TO 'quartzjobs_prod'@'%';
FLUSH PRIVILEGES;
