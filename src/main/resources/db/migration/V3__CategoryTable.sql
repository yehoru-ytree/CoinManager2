CREATE TABLE IF NOT EXISTS bankaggregator.category (
    id           uuid PRIMARY KEY,
    name         text NOT NULL UNIQUE,
    display_name text NOT NULL,
    sheet_row    int  NOT NULL UNIQUE,
    priority     int  NOT NULL DEFAULT 10,
    keywords     jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_other     boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS category_one_other ON bankaggregator.category((is_other)) WHERE is_other;

INSERT INTO bankaggregator.category (id, name, display_name, sheet_row, priority, keywords, is_other) VALUES
    (gen_random_uuid(), 'FLAT',             'Квартира',          0, 70, '["аренда","оренда","квартира","rent","landlord"]'::jsonb, false),
    (gen_random_uuid(), 'UTILITIES',        'Комуналка',         1, 75, '["коммун","комун","свет","електро","газ","вода","тепло","осбб","жек"]'::jsonb, false),
    (gen_random_uuid(), 'GROCERIES',        'Еда',               2, 85, '["\\batb\\b","атб","\\bsilpo\\b","сильпо","\\bvarus\\b","варус","\\bauchan\\b","ашан","\\bmetro\\b","супермаркет","продукт"]'::jsonb, false),
    (gen_random_uuid(), 'FUEL',             'Бензин',            3, 90, '["\\bokko\\b","\\bwog\\b","\\bsocar\\b","\\bbrsm\\b","азс","бензин","дизель","БРСМ-Нафта"]'::jsonb, false),
    (gen_random_uuid(), 'CAR_REPAIR',       'Ремонт авто',       4, 80, '["\\bsto\\b","сто","шиномонтаж","развал","запчаст","сервис","ремонт авто","масло","аккумулятор","Anzhela O.","MAGAZYN PAROM","Ілона Р."]'::jsonb, false),
    (gen_random_uuid(), 'TRANSIT_TAXI',     'Маршрутка/такси',   5, 95, '["\\buber\\b","\\bbolt\\b","\\buklon\\b","\\btaxi\\b","такси","Громадський транспорт"]'::jsonb, false),
    (gen_random_uuid(), 'CAFE_DELIVERY',    'Кафе/Доставка',     6, 92, '["\\bglovo\\b","\\bbolt food\\b","\\brocket\\b","доставка","кафе","ресторан","Булочник"]'::jsonb, false),
    (gen_random_uuid(), 'LEISURE',          'Досуг',             7, 45, '["steam","psn","xbox","кино","cinema","bar","club","Akvarel"]'::jsonb, false),
    (gen_random_uuid(), 'CLOTHING',         'Одежда',            8, 50, '["одежд","обув","clothing","zara","hm","h&m"]'::jsonb, false),
    (gen_random_uuid(), 'HOUSEHOLD_GOODS',  'Хоз.товары',        9, 55, '["epicentr","эпицентр","ikea","хоз","быт","household","PROSTOR","Rozetka","Zooalliance","Аврора"]'::jsonb, false),
    (gen_random_uuid(), 'POKER',            'Покер',            10, 60, '["poker","ggpoker","pokerstars","partypoker"]'::jsonb, false),
    (gen_random_uuid(), 'GYM',              'КОчалка',          11, 60, '["gym","fitness","спортзал","качалк"]'::jsonb, false),
    (gen_random_uuid(), 'HEALTH',           'Здоровье',         12, 65, '["аптека","pharmacy","стомат","клиника","doctor","анализ","мед"]'::jsonb, false),
    (gen_random_uuid(), 'RESERVATION',      'Бронь',            13, 30, '["booking","airbnb","бронь","депозит"]'::jsonb, false),
    (gen_random_uuid(), 'DONATIONS',        'Донаты',           14, 30, '["Поповнення","донат","пожертв","donation"]'::jsonb, false),
    (gen_random_uuid(), 'BEAUTY',           'Красота',          15, 30, '["салон","парикмахер","маникюр","космет","talalai"]'::jsonb, false),
    (gen_random_uuid(), 'SAVINGS',          'Отложили',         16, 30, '["накоп","сбереж","savings"]'::jsonb, false),
    (gen_random_uuid(), 'UNIVERSITY',       'Универ',           17, 30, '["university","универ","оплата обуч","tuition"]'::jsonb, false),
    (gen_random_uuid(), 'WORK_LUNCH',       'Пожрать на работе', 18, 25, '["столов","canteen","обед на работе"]'::jsonb, false),
    (gen_random_uuid(), 'CREDIT_CARD',      'Кредитка',         19, 35, '["погашени.*кредит","credit card","минимальн.*платеж","проценты"]'::jsonb, false),
    (gen_random_uuid(), 'PHONE_INTERNET',   'Телефон/Интернет', 20, 40, '["kyivstar","киевстар","vodafone","lifecell","интернет","internet","isp","пополнени.*тел"]'::jsonb, false),
    (gen_random_uuid(), 'OTHER',            'Прочее',           21, 0,  '[]'::jsonb, true),
    (gen_random_uuid(), 'SUBSCRIPTIONS',    'Подписки',         22, 100, '["\\bnetflix\\b","\\bspotify\\b","\\byoutube premium\\b","\\bicloud\\b","\\bgoogle one\\b","\\bpatreon\\b"]'::jsonb, false);
