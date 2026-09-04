INSERT INTO tb_user(id, username, password, phone, role, status)
SELECT
    100000 + seq.n AS id,
    CONCAT('loaduser', LPAD(seq.n, 4, '0')) AS username,
    '{noop}123456' AS password,
    CONCAT('188', LPAD(seq.n, 8, '0')) AS phone,
    'USER' AS role,
    1 AS status
FROM (
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + 1 AS n
    FROM
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1) thousands
) seq
WHERE seq.n <= 1001
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    password = VALUES(password),
    phone = VALUES(phone),
    role = VALUES(role),
    status = VALUES(status);
