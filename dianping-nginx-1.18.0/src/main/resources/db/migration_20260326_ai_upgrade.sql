-- Run once on existing hmdp database.
-- Adds shop description field for AI assistant + seeds baseline descriptions.

ALTER TABLE `tb_shop`
  ADD COLUMN `shop_desc` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商铺简介，描述经营商品、服务与提示信息' AFTER `open_hours`;

UPDATE `tb_shop` SET `shop_desc` = '主打平价茶餐厅和工作餐，适合朋友小聚与日常吃饭，可点港式奶茶与简餐。' WHERE `id` = 1;
UPDATE `tb_shop` SET `shop_desc` = '主营铜锅涮羊肉与烤肉，晚间营业时间长，适合聚餐。' WHERE `id` = 2;
UPDATE `tb_shop` SET `shop_desc` = '家常杭帮菜与清淡菜品选择较多，适合家庭聚餐和胃口清淡人群。' WHERE `id` = 3;
UPDATE `tb_shop` SET `shop_desc` = '花园氛围西餐厅，适合约会与拍照，提供甜品、饮品和休闲座位。' WHERE `id` = 4;
UPDATE `tb_shop` SET `shop_desc` = '火锅门店，服务流程完善，提供等位休息与夜间营业。' WHERE `id` = 5;
UPDATE `tb_shop` SET `shop_desc` = '老北京涮锅，肉类和热汤为主，适合多人聚餐。' WHERE `id` = 6;
UPDATE `tb_shop` SET `shop_desc` = '烤鱼门店，口味可选酸辣清淡，提供多人套餐。' WHERE `id` = 7;
UPDATE `tb_shop` SET `shop_desc` = '寿司与日料小店，适合轻食与清淡口味。' WHERE `id` = 8;
UPDATE `tb_shop` SET `shop_desc` = '炭火锅门店，主打羊蝎子与牛仔排，适合重口味聚餐。' WHERE `id` = 9;
UPDATE `tb_shop` SET `shop_desc` = 'KTV娱乐门店，含包厢和饮品服务，适合休闲唱歌。' WHERE `id` = 10;
UPDATE `tb_shop` SET `shop_desc` = 'KTV门店，夜场时段丰富，可提供包厢休息与饮品。' WHERE `id` = 11;
UPDATE `tb_shop` SET `shop_desc` = 'KTV门店，适合朋友聚会，含包厢和基础娱乐服务。' WHERE `id` = 12;
UPDATE `tb_shop` SET `shop_desc` = '量贩KTV，支持多人包厢场景，提供饮品与休闲空间。' WHERE `id` = 13;
UPDATE `tb_shop` SET `shop_desc` = 'KTV门店，地理位置便利，适合夜间娱乐和小型聚会。' WHERE `id` = 14;
