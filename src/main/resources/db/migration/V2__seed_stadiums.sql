-- Big6 홈구장. 위치는 경기장 중심 근처 좌표 (펍 거리 계산용이라 수십 m 오차는 상관없다)
INSERT INTO stadium (club, name, name_ko, city, lat, lng, transit) VALUES
('arsenal',   'Emirates Stadium',             '에미레이츠 스타디움',       'London',     51.5549, -0.1084, 'Arsenal역(피커딜리선), Holloway Road역, Highbury & Islington역'),
('chelsea',   'Stamford Bridge',              '스탬퍼드 브리지',           'London',     51.4817, -0.1910, 'Fulham Broadway역(디스트릭트선)'),
('liverpool', 'Anfield',                      '안필드',                    'Liverpool',  53.4308, -2.9608, 'Liverpool Lime Street역에서 버스 또는 도보·택시'),
('mancity',   'Etihad Stadium',               '에티하드 스타디움',         'Manchester', 53.4831, -2.2004, 'Etihad Campus 트램역 (Manchester Piccadilly역에서 트램)'),
('manutd',    'Old Trafford',                 '올드 트래퍼드',             'Manchester', 53.4631, -2.2913, 'Old Trafford 트램역, 경기일엔 Manchester United Football Ground역'),
('tottenham', 'Tottenham Hotspur Stadium',    '토트넘 홋스퍼 스타디움',    'London',     51.6043, -0.0665, 'White Hart Lane역(오버그라운드), Northumberland Park역, Seven Sisters역');
