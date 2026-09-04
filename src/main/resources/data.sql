INSERT IGNORE INTO tb_user(id, username, password, phone, role, status)
VALUES
  (1, 'admin', '{noop}123456', '19901541686', 'ADMIN', 1),
  (2, 'zhangsan', '{noop}123456', '17715993804', 'USER', 1);

INSERT IGNORE INTO tb_classroom(id, building_name, room_number, capacity, room_type, equipment, status)
VALUES
  (1, 'Computer Building', '106', 180, 'LAB', 'Projector,Computer Room,Whiteboard', 1),
  (2, 'Computer Building', '113', 200, 'LAB', 'Projector,Computer Room', 1),
  (3, 'Lecture Hall', '101', 300, 'LECTURE', 'Projector,Audio,Recording', 1),
  (4, 'Lecture Hall', '201', 160, 'LECTURE', 'Projector,Audio', 1),
  (5, 'Sanjiang Building', '0203', 45, 'SEMINAR', 'Whiteboard', 1),
  (6, 'Sanjiang Building', '0204', 46, 'SEMINAR', 'Whiteboard', 1);
