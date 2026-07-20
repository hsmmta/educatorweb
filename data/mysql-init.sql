-- EducatorWeb MySQL Initialization
CREATE DATABASE IF NOT EXISTS educatorweb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root';
FLUSH PRIVILEGES;
