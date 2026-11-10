-- Manchester United Football Ground역은 구단 요청으로 경기일 운행이 무기한 중단됐다 (운영사 안내 기준)
UPDATE stadium
SET transit = 'Old Trafford 트램역(경기장까지 도보 약 10분) 또는 Wharfside 트램역'
WHERE club = 'manutd';
