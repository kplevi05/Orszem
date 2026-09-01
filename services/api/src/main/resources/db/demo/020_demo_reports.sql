-- Őrszem Demo v1 — reprezentatív demo report seed
-- KIZÁRÓLAG local/demo környezetben futtatható.
-- Baseline reset után:
--   total=120, today=16, NEW=8, IN_PROGRESS=6, ARCHIVED=106, active=14
-- A "today" rekordok a futtatás napjához (Europe/Budapest) igazodnak.

DO $$
DECLARE
    found_count integer;
BEGIN
    SELECT count(*) INTO found_count
    FROM event_types
    WHERE code IN ('AGGRESSIVE_BEHAVIOR', 'DOOR_OBSTRUCTION', 'FIGHT', 'FIRE_OR_SMOKE', 'GRAFFITI', 'ILLNESS', 'INJURED_PERSON', 'KNIFE_ATTACK', 'LITTERING', 'LOUD_BEHAVIOR', 'PASSENGER_HARASSMENT', 'ROBBERY', 'SMOKING', 'STAFF_HARASSMENT', 'SUSPECTED_DRUG_USE', 'SUSPICIOUS_PACKAGE', 'THEFT', 'UNCONSCIOUS_PERSON', 'VANDALISM', 'WEAPON_THREAT');
    IF found_count <> 20 THEN
        RAISE EXCEPTION 'Demo report seed: hiányos event catalog. Várt eseménytípusok: 20, megtalált: %', found_count;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'demo.service' AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Demo report seed: demo.service felhasználó nem található vagy inaktív.';
    END IF;
END $$;

WITH seed(id, event_type_code, train_identifier, settlement, status, occurred_at) AS (
    VALUES
        ('73c81c0f-2b15-5849-ab0d-e4a9aefaf292'::uuid, 'FIGHT', 'IC 123', 'Budapest', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.96) AT TIME ZONE 'Europe/Budapest')),
        ('fb4b692d-c0cd-530d-9929-6c9632c701ad'::uuid, 'SUSPICIOUS_PACKAGE', 'S70', 'Vác', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.908) AT TIME ZONE 'Europe/Budapest')),
        ('4f000e8f-4c46-5808-a435-1ff55469f8fe'::uuid, 'LOUD_BEHAVIOR', 'EC 45', 'Budapest', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.856) AT TIME ZONE 'Europe/Budapest')),
        ('76b5b590-323f-50c6-a180-13272a271609'::uuid, 'THEFT', 'IC 245', 'Gödöllő', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.804) AT TIME ZONE 'Europe/Budapest')),
        ('13925d1a-40cd-5859-b9e1-094829704ea5'::uuid, 'AGGRESSIVE_BEHAVIOR', 'Z30', 'Szolnok', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.752) AT TIME ZONE 'Europe/Budapest')),
        ('42228fcb-400a-5884-b8b6-13b35b7d02ec'::uuid, 'KNIFE_ATTACK', 'IC 123', 'Budapest', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.7) AT TIME ZONE 'Europe/Budapest')),
        ('b5d71205-1051-593b-b0e0-b1d96a9af6ac'::uuid, 'VANDALISM', 'G70', 'Nagykáta', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.648) AT TIME ZONE 'Europe/Budapest')),
        ('60b71625-1646-5db4-a4ba-ef505d96ac8b'::uuid, 'GRAFFITI', 'S70', 'Szolnok', 'NEW', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.596) AT TIME ZONE 'Europe/Budapest')),
        ('67a864ca-bcde-5b5a-9244-34f7c2592ef1'::uuid, 'UNCONSCIOUS_PERSON', 'S70', 'Veresegyház', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.544) AT TIME ZONE 'Europe/Budapest')),
        ('c8715ee8-3369-5b6d-8637-dd674c9fb3f8'::uuid, 'VANDALISM', 'R 452', 'Veresegyház', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.492) AT TIME ZONE 'Europe/Budapest')),
        ('16daaf51-993d-50d2-973f-e134f17dc92a'::uuid, 'LITTERING', 'S70', 'Monor', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.44) AT TIME ZONE 'Europe/Budapest')),
        ('f1538d60-48df-5758-b959-437cd73461fd'::uuid, 'WEAPON_THREAT', 'S60', 'Monor', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.388) AT TIME ZONE 'Europe/Budapest')),
        ('cb124023-c320-5875-82aa-65c13b87c108'::uuid, 'UNCONSCIOUS_PERSON', 'R 452', 'Monor', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.336) AT TIME ZONE 'Europe/Budapest')),
        ('0106c0b9-1422-560c-a271-377c7606fc61'::uuid, 'LITTERING', 'R 452', 'Cegléd', 'IN_PROGRESS', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.284) AT TIME ZONE 'Europe/Budapest')),
        ('a0b72db8-16f5-55d3-8a59-26dffbc284ac'::uuid, 'LOUD_BEHAVIOR', 'G70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.232) AT TIME ZONE 'Europe/Budapest')),
        ('6a39d232-7415-525c-8208-7a8f8b315c7e'::uuid, 'LITTERING', 'S60', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') + ((current_timestamp AT TIME ZONE 'Europe/Budapest') - date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest')) * 0.18) AT TIME ZONE 'Europe/Budapest')),
        ('ab119065-860b-577f-8c64-db499a595da2'::uuid, 'LOUD_BEHAVIOR', 'IC 197', 'Nagykáta', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '1 days' + interval '12 hours 28 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('6f6461c1-4124-5fd5-8950-d3dc54b47a74'::uuid, 'LOUD_BEHAVIOR', 'S60', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '1 days' + interval '17 hours 41 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('4d93808e-1e96-52e3-8d2a-4c474e572446'::uuid, 'ROBBERY', 'S60', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '1 days' + interval '7 hours 54 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('4586b4ca-bcfd-5132-8897-a11e15ac6f89'::uuid, 'PASSENGER_HARASSMENT', 'EC 45', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '1 days' + interval '12 hours 7 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('63efc89e-ef18-505d-bb32-815f10078666'::uuid, 'DOOR_OBSTRUCTION', 'Z30', 'Aszód', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '2 days' + interval '17 hours 20 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('3b9acd86-a963-5bdf-bb3c-cda9459bf55c'::uuid, 'PASSENGER_HARASSMENT', 'IC 245', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '2 days' + interval '7 hours 33 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('3f20097f-aff6-5ff7-9979-c2d34f29a35d'::uuid, 'SUSPECTED_DRUG_USE', 'IR 87', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '2 days' + interval '12 hours 46 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('25dbf969-cddb-53ad-a4f1-55b60d22dfbf'::uuid, 'LITTERING', 'S70', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '3 days' + interval '17 hours 59 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('16e7e8d6-8e2a-5261-bf71-de18ae9726d0'::uuid, 'LOUD_BEHAVIOR', 'IC 123', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '3 days' + interval '7 hours 12 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('51538f06-fbc6-5ddc-ba8c-85aa86664158'::uuid, 'UNCONSCIOUS_PERSON', 'IC 123', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '3 days' + interval '12 hours 25 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ab13607f-adb3-5be7-966f-c54d2bcc2399'::uuid, 'WEAPON_THREAT', 'Z30', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '3 days' + interval '17 hours 38 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('b9a78a04-79c0-5c59-903c-5d6f5cadf569'::uuid, 'LITTERING', 'EC 45', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '4 days' + interval '7 hours 51 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e3766764-e1a1-58a4-8237-f0aa0a61ab0b'::uuid, 'SMOKING', 'Z30', 'Nagykáta', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '4 days' + interval '12 hours 4 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('5246bca5-41a8-5dcb-b6b5-12f343c9ca7d'::uuid, 'ILLNESS', 'IC 123', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '4 days' + interval '17 hours 17 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('9459d870-b948-553c-b6f4-842b9032131a'::uuid, 'THEFT', 'IC 123', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '5 days' + interval '7 hours 30 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ed4d76e5-04ca-528b-9244-6e5e87617919'::uuid, 'INJURED_PERSON', 'EC 45', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '5 days' + interval '12 hours 43 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('110cc6e9-2930-5004-a457-2edb1003a0f2'::uuid, 'ILLNESS', 'IC 197', 'Nagykáta', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '5 days' + interval '17 hours 56 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('774a6b2d-6953-56ad-810f-9dcbfc991b19'::uuid, 'LITTERING', 'Z30', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '5 days' + interval '7 hours 9 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ae67e886-3095-5908-8a7e-9969a5d08c6b'::uuid, 'LITTERING', 'R 452', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '6 days' + interval '12 hours 22 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('594fdb22-5529-5301-8773-c7a403e953b1'::uuid, 'SMOKING', 'G70', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '6 days' + interval '17 hours 35 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ebb1209c-4c20-5912-adbc-62aee3bc322d'::uuid, 'SUSPECTED_DRUG_USE', 'IC 245', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '6 days' + interval '7 hours 48 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('8a69ee04-cf0c-52b6-a9f8-a5d803898887'::uuid, 'SUSPECTED_DRUG_USE', 'EC 45', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '7 days' + interval '12 hours 1 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('0e399dec-9966-501f-8b9e-1997e70d9d62'::uuid, 'KNIFE_ATTACK', 'EC 45', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '7 days' + interval '17 hours 14 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('89dce63f-1063-56e3-abe3-62ff1b90440d'::uuid, 'LITTERING', 'G70', 'Gyömrő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '7 days' + interval '7 hours 27 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('3852e5e5-751a-5d4f-b156-5a8e85d5047c'::uuid, 'LOUD_BEHAVIOR', 'S70', 'Gyömrő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '7 days' + interval '12 hours 40 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e079b0bc-4e9c-5f38-88ad-0de09c0829a3'::uuid, 'FIGHT', 'S60', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '8 days' + interval '17 hours 53 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('6224676d-d5ac-5472-b537-de096da2997a'::uuid, 'STAFF_HARASSMENT', 'S70', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '8 days' + interval '7 hours 6 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('d98019b3-bd81-54dc-a115-8169f31a131b'::uuid, 'SMOKING', 'IC 245', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '8 days' + interval '12 hours 19 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a44a6507-7723-5ba2-9fbc-5dc7ab162ca3'::uuid, 'LITTERING', 'G70', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '9 days' + interval '17 hours 32 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('7d951337-c3de-5488-a932-76112de75424'::uuid, 'LOUD_BEHAVIOR', 'IC 123', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '9 days' + interval '7 hours 45 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('55b2eece-63c4-5c47-81d5-b350e895db2a'::uuid, 'FIRE_OR_SMOKE', 'S70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '9 days' + interval '12 hours 58 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ea937650-c122-53e9-9fb7-b78a79a55b9d'::uuid, 'VANDALISM', 'IR 87', 'Veresegyház', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '9 days' + interval '17 hours 11 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('71a1eb92-c53b-5dcb-930d-6295f2ac4d50'::uuid, 'PASSENGER_HARASSMENT', 'IC 245', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '10 days' + interval '7 hours 24 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('c94806b9-c66e-512f-8467-275d841a2de1'::uuid, 'DOOR_OBSTRUCTION', 'Z30', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '10 days' + interval '12 hours 37 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('1271313e-4aaa-56ad-b3be-045ef4b809ef'::uuid, 'THEFT', 'IC 123', 'Gyömrő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '10 days' + interval '17 hours 50 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('6055a615-6fc2-5baf-9da9-13d9d4de067c'::uuid, 'LITTERING', 'IR 87', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '11 days' + interval '7 hours 3 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('2056c997-ad2f-532d-9335-f9af78ae2f23'::uuid, 'STAFF_HARASSMENT', 'IC 123', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '11 days' + interval '12 hours 16 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('492ba71d-6703-505c-ab75-cd12e5dd16b8'::uuid, 'LOUD_BEHAVIOR', 'IC 123', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '11 days' + interval '17 hours 29 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('cbcdb0d1-4723-5e51-b006-12d22ea714b6'::uuid, 'SUSPICIOUS_PACKAGE', 'Z30', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '11 days' + interval '7 hours 42 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e7df8b8d-1c3f-5b61-96f9-42c4e06023d0'::uuid, 'SMOKING', 'IC 123', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '12 days' + interval '12 hours 55 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('680fbdba-c59a-575e-bc3c-3840d6b443dc'::uuid, 'THEFT', 'EC 45', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '12 days' + interval '17 hours 8 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('33d7f3d7-4b72-5f3e-8994-47b9f67e18b3'::uuid, 'LOUD_BEHAVIOR', 'IC 245', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '12 days' + interval '7 hours 21 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('95cf880f-324e-5e84-95df-5d6f200d898d'::uuid, 'LOUD_BEHAVIOR', 'EC 45', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '13 days' + interval '12 hours 34 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('5c593a83-f279-5b5b-a52f-c137ffa1c6dd'::uuid, 'GRAFFITI', 'IC 123', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '13 days' + interval '17 hours 47 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('d25d7818-7864-5e41-ac90-8596ea3768cc'::uuid, 'LITTERING', 'S80', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '13 days' + interval '7 hours 0 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('79affcd8-fb87-5504-abd5-806c350f8f79'::uuid, 'AGGRESSIVE_BEHAVIOR', 'IC 123', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '13 days' + interval '12 hours 13 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('90387bc8-c35b-55e4-b139-69482e0987ba'::uuid, 'LOUD_BEHAVIOR', 'S70', 'Gyömrő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '14 days' + interval '17 hours 26 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('7760ffb4-c827-53e2-81fa-4b462ef5f4fa'::uuid, 'LOUD_BEHAVIOR', 'S70', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '14 days' + interval '7 hours 39 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a6daef8b-62f2-5323-b2fa-5aa0a97000a8'::uuid, 'FIGHT', 'S80', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '14 days' + interval '12 hours 52 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('0a458302-63cf-5898-8fd6-7e2b346aa7a5'::uuid, 'LOUD_BEHAVIOR', 'S80', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '15 days' + interval '17 hours 5 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('4144aa6e-37c4-5a67-a69a-cc769c8e3a28'::uuid, 'FIRE_OR_SMOKE', 'G70', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '15 days' + interval '7 hours 18 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('80a6cbca-0f5f-547f-90ce-bc18e0136e45'::uuid, 'LITTERING', 'Z30', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '15 days' + interval '12 hours 31 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('750cc14f-71c3-5d56-8781-5d9d9b830184'::uuid, 'VANDALISM', 'IC 245', 'Aszód', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '16 days' + interval '17 hours 44 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('0f885179-725d-5a73-a479-bb66c1b0e301'::uuid, 'FIGHT', 'EC 45', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '16 days' + interval '7 hours 57 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('2b341e7b-5064-5a64-a85a-14334062a1a0'::uuid, 'FIGHT', 'S60', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '16 days' + interval '12 hours 10 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('963fa5f8-973f-52ed-a259-074cec1d51a4'::uuid, 'VANDALISM', 'EC 45', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '16 days' + interval '17 hours 23 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('9f16ef5c-b543-5be4-993c-12aef9238174'::uuid, 'SUSPICIOUS_PACKAGE', 'IC 197', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '17 days' + interval '7 hours 36 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e6aab9fa-66d6-52ab-9cca-c10befedd232'::uuid, 'VANDALISM', 'IC 245', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '17 days' + interval '12 hours 49 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('907ce588-deac-5b3f-a379-a7e43ddf374c'::uuid, 'ROBBERY', 'IC 123', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '17 days' + interval '17 hours 2 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('725fbdb7-a7b6-5b1f-af0a-fa99f0141408'::uuid, 'LOUD_BEHAVIOR', 'G70', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '18 days' + interval '7 hours 15 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('445429ef-efef-54ba-bb85-3ab3a3c2326a'::uuid, 'SMOKING', 'IC 245', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '18 days' + interval '12 hours 28 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('b4b6be4e-ec8c-58e2-92a7-d575b36e2ea1'::uuid, 'SMOKING', 'S70', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '18 days' + interval '17 hours 41 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('74f95044-2301-591c-a7e6-1c527e1e1b1e'::uuid, 'PASSENGER_HARASSMENT', 'S80', 'Nagykáta', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '18 days' + interval '7 hours 54 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('7c2c1296-fb93-5e32-a196-f276fafc8be9'::uuid, 'SUSPICIOUS_PACKAGE', 'S70', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '19 days' + interval '12 hours 7 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a419c5bb-e037-500c-856b-2b6f04312dc8'::uuid, 'THEFT', 'Z30', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '19 days' + interval '17 hours 20 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('6e8e2b27-2f26-5042-86b9-5e3cd6f1fbcc'::uuid, 'ILLNESS', 'Z30', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '19 days' + interval '7 hours 33 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('daa060a3-328a-50b6-9947-55634a678040'::uuid, 'AGGRESSIVE_BEHAVIOR', 'G70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '20 days' + interval '12 hours 46 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('54ec881a-5e1a-5a57-ad1b-15a4b3e4c441'::uuid, 'AGGRESSIVE_BEHAVIOR', 'Z30', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '20 days' + interval '17 hours 59 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('dcf6b5a2-9181-5b8a-8d89-a8efb2e80e25'::uuid, 'SUSPECTED_DRUG_USE', 'EC 45', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '20 days' + interval '7 hours 12 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('71dbdb24-811d-5ea1-9ae6-8deeb2cd999b'::uuid, 'THEFT', 'IC 245', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '20 days' + interval '12 hours 25 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('480b6f28-02d4-507a-8cdf-17b1a27979a6'::uuid, 'PASSENGER_HARASSMENT', 'IC 123', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '21 days' + interval '17 hours 38 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('3c61cf30-c846-55b7-9f59-30f0585b1617'::uuid, 'LOUD_BEHAVIOR', 'S70', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '21 days' + interval '7 hours 51 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('c841d218-b707-577c-9fb1-0f260a13dc99'::uuid, 'LOUD_BEHAVIOR', 'EC 45', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '21 days' + interval '12 hours 4 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a6143d67-203b-510e-b323-3f311839b789'::uuid, 'THEFT', 'IC 123', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '22 days' + interval '17 hours 17 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('1cbfc4d4-0b6c-5d59-b0d7-5eeb8a60807c'::uuid, 'ILLNESS', 'Z30', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '22 days' + interval '7 hours 30 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('cf69f0f2-d958-5276-904f-ad2db68d894c'::uuid, 'SUSPICIOUS_PACKAGE', 'S70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '22 days' + interval '12 hours 43 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('ee39a2a1-d52c-5433-9244-316eeed674fe'::uuid, 'INJURED_PERSON', 'S80', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '22 days' + interval '17 hours 56 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e3ca58c9-1501-574e-b754-519072936eea'::uuid, 'LITTERING', 'IR 87', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '23 days' + interval '7 hours 9 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('9478fb84-c757-5f60-94f9-2db0ac3a8696'::uuid, 'SUSPICIOUS_PACKAGE', 'IC 245', 'Veresegyház', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '23 days' + interval '12 hours 22 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('01224592-7e5a-5862-9009-7cfea8af4422'::uuid, 'VANDALISM', 'IC 123', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '23 days' + interval '17 hours 35 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('401e04f4-e6fc-518c-829f-b5435a30b1c8'::uuid, 'VANDALISM', 'EC 45', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '24 days' + interval '7 hours 48 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('f87e0164-d609-5a53-a3ef-574669dbaf4c'::uuid, 'THEFT', 'IC 123', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '24 days' + interval '12 hours 1 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('58a9d407-1b8a-5f62-825a-646e99050f98'::uuid, 'PASSENGER_HARASSMENT', 'S70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '24 days' + interval '17 hours 14 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('8e10f557-8800-5410-86d7-df3b143c1cb0'::uuid, 'FIGHT', 'IC 197', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '24 days' + interval '7 hours 27 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a3f25c2d-b6ed-5ff5-83a1-9dfd21ac80c7'::uuid, 'FIGHT', 'IC 245', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '25 days' + interval '12 hours 40 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('e88dc6c1-27e1-5a18-a636-6e7ec4c2d448'::uuid, 'VANDALISM', 'S70', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '25 days' + interval '17 hours 53 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('8238b64e-06d5-5548-bb6b-5d56db469be4'::uuid, 'LOUD_BEHAVIOR', 'G70', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '25 days' + interval '7 hours 6 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('1661d53e-4b67-5e39-bade-6009e11ef09a'::uuid, 'FIGHT', 'IR 87', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '26 days' + interval '12 hours 19 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('7ae97bd2-911c-5259-9d3e-980f0d2cf754'::uuid, 'FIGHT', 'IR 87', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '26 days' + interval '17 hours 32 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('1087d909-0ad3-5bfa-b760-97ea7cb0afe4'::uuid, 'VANDALISM', 'S60', 'Aszód', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '26 days' + interval '7 hours 45 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('047fcf25-d1fb-5277-b6bf-e54dd246eef5'::uuid, 'LITTERING', 'IC 123', 'Gyömrő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '26 days' + interval '12 hours 58 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('c7fada28-6c82-57a0-94de-973ec61a17e2'::uuid, 'KNIFE_ATTACK', 'S60', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '27 days' + interval '17 hours 11 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('cb4a0deb-7135-506a-b2f6-26c19c40d465'::uuid, 'FIGHT', 'S60', 'Hatvan', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '27 days' + interval '7 hours 24 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('0a47be37-112d-5cee-a4e4-8158111097f0'::uuid, 'PASSENGER_HARASSMENT', 'S80', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '27 days' + interval '12 hours 37 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('078c8c76-9922-5f6a-ace9-1db883f212e1'::uuid, 'FIGHT', 'IR 87', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '28 days' + interval '17 hours 50 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('fc6cc01d-e497-50b9-a55a-fc9ec850e8cd'::uuid, 'FIGHT', 'S80', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '28 days' + interval '7 hours 3 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('fad34a0c-ac14-52af-8355-52bc423043a0'::uuid, 'SMOKING', 'IC 123', 'Gödöllő', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '28 days' + interval '12 hours 16 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('89190a5b-c79f-5cc8-9abe-b251ddf305cb'::uuid, 'ILLNESS', 'S70', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '28 days' + interval '17 hours 29 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('1e80475b-af4d-57fa-bd07-a790adabc4ce'::uuid, 'AGGRESSIVE_BEHAVIOR', 'EC 45', 'Szolnok', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '29 days' + interval '7 hours 42 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('020a7ec1-b86a-5dbe-bfbc-7c872edb8c28'::uuid, 'SMOKING', 'IR 87', 'Vác', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '29 days' + interval '12 hours 55 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('f2e7759d-10d1-50c2-bb06-37286d655994'::uuid, 'GRAFFITI', 'IC 245', 'Nagykáta', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '29 days' + interval '17 hours 8 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('a51e11b5-8c4d-52b0-b8cf-825a6a097efa'::uuid, 'LOUD_BEHAVIOR', 'G70', 'Monor', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '30 days' + interval '7 hours 21 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('da636746-9102-55fe-a12d-33938612d808'::uuid, 'THEFT', 'IC 197', 'Budapest', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '30 days' + interval '12 hours 34 minutes') AT TIME ZONE 'Europe/Budapest')),
        ('283f766a-72ce-5dec-9f1f-4aa84e5f4e8f'::uuid, 'STAFF_HARASSMENT', 'EC 45', 'Cegléd', 'ARCHIVED', ((date_trunc('day', current_timestamp AT TIME ZONE 'Europe/Budapest') - interval '30 days' + interval '17 hours 47 minutes') AT TIME ZONE 'Europe/Budapest'))
),
actor AS (
    SELECT id AS actor_id
    FROM users
    WHERE username = 'demo.service'
),
resolved AS (
    SELECT
        s.*,
        et.id AS event_type_id,
        a.actor_id,
        LEAST(s.occurred_at + interval '2 minutes', current_timestamp) AS received_at
    FROM seed s
    JOIN event_types et ON et.code = s.event_type_code
    CROSS JOIN actor a
)
INSERT INTO reports (
    id,
    event_type_id,
    train_identifier,
    settlement,
    occurred_at,
    received_at,
    status,
    accepted_at,
    archived_at,
    accepted_by_user_id,
    archived_by_user_id,
    created_at,
    updated_at
)
SELECT
    id,
    event_type_id,
    train_identifier,
    settlement,
    occurred_at,
    received_at,
    status,
    CASE
        WHEN status IN ('IN_PROGRESS', 'ARCHIVED')
        THEN LEAST(received_at + interval '7 minutes', current_timestamp)
        ELSE NULL
    END AS accepted_at,
    CASE
        WHEN status = 'ARCHIVED'
        THEN LEAST(received_at + interval '37 minutes', current_timestamp)
        ELSE NULL
    END AS archived_at,
    CASE WHEN status IN ('IN_PROGRESS', 'ARCHIVED') THEN actor_id ELSE NULL END,
    CASE WHEN status = 'ARCHIVED' THEN actor_id ELSE NULL END,
    received_at,
    CASE
        WHEN status = 'ARCHIVED' THEN LEAST(received_at + interval '37 minutes', current_timestamp)
        WHEN status = 'IN_PROGRESS' THEN LEAST(received_at + interval '7 minutes', current_timestamp)
        ELSE received_at
    END
FROM resolved
ON CONFLICT (id) DO UPDATE SET
    event_type_id = EXCLUDED.event_type_id,
    train_identifier = EXCLUDED.train_identifier,
    settlement = EXCLUDED.settlement,
    occurred_at = EXCLUDED.occurred_at,
    received_at = EXCLUDED.received_at,
    status = EXCLUDED.status,
    accepted_at = EXCLUDED.accepted_at,
    archived_at = EXCLUDED.archived_at,
    accepted_by_user_id = EXCLUDED.accepted_by_user_id,
    archived_by_user_id = EXCLUDED.archived_by_user_id,
    updated_at = EXCLUDED.updated_at;
