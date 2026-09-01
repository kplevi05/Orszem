-- Őrszem Demo v1 — event catalog seed
-- Flyway repeatable migration, ONLY local/demo profile location
-- Catalog version: demo-v1.0

WITH seed(id, code, label, sort_order, active) AS (
    VALUES
        ('df861f1b-023d-59da-82f5-eff2fd925a48'::uuid, 'VIOLENCE_DANGER', 'Erőszak és közvetlen veszély', 10, true),
        ('8439e91a-a8bd-51da-9571-7e255614070e'::uuid, 'DISTURBANCE_HARASSMENT', 'Rendzavarás és zaklatás', 20, true),
        ('0c996cd5-bcf5-5a1c-b3ac-3e616fe36f9d'::uuid, 'THEFT_PROPERTY', 'Lopás és vagyon elleni esemény', 30, true),
        ('e2ce825b-44cc-5cc0-95bd-03d471b321ca'::uuid, 'SUSPICIOUS_ACTIVITY', 'Gyanús személy, tárgy vagy tevékenység', 40, true),
        ('690fe8ad-eb28-50a0-a6b6-929e5692917c'::uuid, 'MEDICAL_WELFARE', 'Egészségügyi és segítségnyújtási esemény', 50, true),
        ('cbe8fd3d-6466-5739-bb78-3b2432e72777'::uuid, 'SAFETY_HAZARD', 'Közlekedésbiztonsági és műszaki veszély', 60, true),
        ('a344875a-25fa-5310-9aaf-22bab8760fcc'::uuid, 'RULE_VIOLATION_OTHER', 'Szabályszegés és egyéb biztonsági esemény', 70, true)
)
INSERT INTO event_categories (id, code, label, sort_order, active)
SELECT id, code, label, sort_order, active FROM seed
ON CONFLICT (code) DO UPDATE SET
    label = EXCLUDED.label,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    updated_at = now();

WITH seed(id, category_code, code, label, description, sort_order, active) AS (
    VALUES
        ('49d4dcf1-e227-5fd0-a0d1-631a0d7c6bd7'::uuid, 'VIOLENCE_DANGER', 'FIGHT', 'Verekedés', 'Két vagy több személy közötti fizikai konfliktus.', 10, true),
        ('12b90cf8-da85-5ed1-9113-627b81ad2214'::uuid, 'VIOLENCE_DANGER', 'KNIFE_ATTACK', 'Késelés', 'Késsel vagy más szúró-vágó eszközzel elkövetett támadás vagy annak közvetlen észlelése.', 20, true),
        ('e22bdc11-ee09-5d71-9a82-491640bd42a5'::uuid, 'VIOLENCE_DANGER', 'PHYSICAL_ASSAULT', 'Fizikai bántalmazás', 'Egy személy fizikai bántalmazása, amely nem verekedésként írható le.', 30, true),
        ('151e0958-8b74-5d21-9fad-cea629139408'::uuid, 'VIOLENCE_DANGER', 'THREAT', 'Fenyegetés', 'Személy elleni komoly verbális vagy egyértelmű fenyegetés.', 40, true),
        ('a2de2852-5ca0-5374-b922-a82e5e6dbd2b'::uuid, 'VIOLENCE_DANGER', 'WEAPON_THREAT', 'Fegyverrel fenyegetés', 'Fegyverrel vagy fegyvernek látszó eszközzel történő fenyegetés.', 50, true),
        ('e54c0643-2517-5474-b933-d3eaf1e4ffd5'::uuid, 'VIOLENCE_DANGER', 'ROBBERY', 'Rablás', 'Erőszakkal vagy fenyegetéssel történő értékelvétel.', 60, true),
        ('3f65c847-597f-565e-ae8c-ff2dcf41b5c0'::uuid, 'VIOLENCE_DANGER', 'PASSENGER_ASSAULT', 'Utas megtámadása', 'Utas elleni közvetlen fizikai támadás.', 70, true),
        ('2a2c1e21-e97b-58dd-84ee-dd482ae3a635'::uuid, 'VIOLENCE_DANGER', 'STAFF_ASSAULT', 'Személyzet megtámadása', 'Vasúti vagy szolgálati személyzet elleni közvetlen fizikai támadás.', 80, true),
        ('8527fb09-33c0-5a85-ab01-9e0f151d5170'::uuid, 'VIOLENCE_DANGER', 'GROUP_CONFLICT', 'Csoportos konfliktus', 'Több személyt vagy csoportot érintő, eszkalálódó fizikai konfliktus.', 90, true),
        ('375463ef-c2f5-543d-996b-fa91c6945150'::uuid, 'VIOLENCE_DANGER', 'SEXUAL_ASSAULT_SUSPECTED', 'Szexuális erőszak gyanúja', 'Szexuális jellegű kényszerítés vagy erőszak észlelésének gyanúja.', 100, true),
        ('9f9b734d-422f-57aa-b62f-1a35e3d349b2'::uuid, 'DISTURBANCE_HARASSMENT', 'LOUD_BEHAVIOR', 'Hangoskodás', 'Tartósan vagy szélsőségesen zavaró hangoskodás.', 10, true),
        ('79cf13c0-033b-540e-a5e9-5eebd5639da8'::uuid, 'DISTURBANCE_HARASSMENT', 'SHOUTING', 'Kiabálás', 'A környezetet zavaró vagy konfliktushoz kapcsolódó kiabálás.', 20, true),
        ('b52143ef-aa21-56ec-87ef-b5878a22143c'::uuid, 'DISTURBANCE_HARASSMENT', 'AGGRESSIVE_BEHAVIOR', 'Agresszív viselkedés', 'Fenyegető, támadó vagy erősen agresszív magatartás fizikai támadás nélkül.', 30, true),
        ('0660ce72-3895-574a-acc5-f0330eff463c'::uuid, 'DISTURBANCE_HARASSMENT', 'DISORDERLY_CONDUCT', 'Rendbontás', 'A rendet vagy az utasok biztonságérzetét jelentősen zavaró magatartás.', 40, true),
        ('3cb74333-408f-5026-b482-032b6c0e021d'::uuid, 'DISTURBANCE_HARASSMENT', 'PASSENGER_HARASSMENT', 'Utasok zaklatása', 'Egy vagy több utas ismételt vagy célzott zaklatása.', 50, true),
        ('3ec1c02e-aa66-5690-b645-bbc5ea0c6625'::uuid, 'DISTURBANCE_HARASSMENT', 'STAFF_HARASSMENT', 'Személyzet zaklatása', 'Vasúti vagy szolgálati személyzet célzott zaklatása.', 60, true),
        ('fb0c0da7-5df8-5dc2-b7e7-3323a61de653'::uuid, 'DISTURBANCE_HARASSMENT', 'SEXUAL_HARASSMENT', 'Szexuális zaklatás', 'Nem kívánt, szexuális jellegű zaklató magatartás.', 70, true),
        ('77d3ec33-b4a4-5429-96ba-19c67926fa5d'::uuid, 'DISTURBANCE_HARASSMENT', 'VERBAL_CONFLICT', 'Szóbeli konfliktus', 'Heves vita vagy verbális konfliktus, amely még nem vált fizikaivá.', 80, true),
        ('6e1917e1-57e3-58c9-b5b0-3c47528d095c'::uuid, 'DISTURBANCE_HARASSMENT', 'INTOXICATED_PERSON', 'Erősen ittas személy', 'Erősen ittas személy, akinek állapota rendzavarást vagy biztonsági problémát okoz.', 90, true),
        ('8132ffd7-9791-5dce-98c8-d39ab221406d'::uuid, 'DISTURBANCE_HARASSMENT', 'SUSPECTED_DRUG_USE', 'Kábítószer-használat gyanúja', 'Kábítószer vagy más tiltott szer használatának észlelt gyanúja.', 100, true),
        ('bdb1453c-fecd-599b-ac4b-4c251bdc6869'::uuid, 'THEFT_PROPERTY', 'THEFT', 'Lopás', 'Tulajdon jogosulatlan eltulajdonítása.', 10, true),
        ('4c58d06b-b357-5bca-9ee0-fea8ee077c2b'::uuid, 'THEFT_PROPERTY', 'ATTEMPTED_THEFT', 'Lopási kísérlet', 'Észlelt, de be nem fejezett lopási kísérlet.', 20, true),
        ('83af7822-3666-53b6-af8a-84400f9c0ff1'::uuid, 'THEFT_PROPERTY', 'PICKPOCKETING', 'Zsebtolvajlás', 'Személyes tárgy észrevétlen eltulajdonítása vagy annak kísérlete.', 30, true),
        ('7cae9215-a3d8-5308-a23b-9492d17b1d03'::uuid, 'THEFT_PROPERTY', 'BAGGAGE_THEFT', 'Csomag vagy poggyász eltulajdonítása', 'Csomag, táska vagy poggyász eltulajdonítása.', 40, true),
        ('50ce2fd5-08a9-55ac-8d11-43059757ed2f'::uuid, 'THEFT_PROPERTY', 'VANDALISM', 'Rongálás', 'Vasúti vagy utastéri tulajdon szándékos megrongálása.', 50, true),
        ('2d517d40-ab46-5b94-951f-68c6ef81ed0e'::uuid, 'THEFT_PROPERTY', 'GRAFFITI', 'Graffiti', 'Vasúti jármű vagy berendezés összefirkálása, festése.', 60, true),
        ('3ae139fc-a366-57c9-9ee6-0425f1d4ae3c'::uuid, 'THEFT_PROPERTY', 'SEAT_DAMAGE', 'Ülés megrongálása', 'Ülés vagy annak tartozékainak szándékos megrongálása.', 70, true),
        ('b65af358-9bd9-59a7-9ec8-934aa08076af'::uuid, 'THEFT_PROPERTY', 'WINDOW_DAMAGE', 'Ablak megrongálása', 'Vonatablak megrongálása vagy betörése.', 80, true),
        ('d5af7358-1592-51d2-8478-35756b063bb6'::uuid, 'THEFT_PROPERTY', 'TRAIN_EQUIPMENT_DAMAGE', 'Vonati berendezés megrongálása', 'A jármű egyéb utastéri vagy műszaki berendezésének megrongálása.', 90, true),
        ('167d4754-5e71-5e9a-8710-409a7a7b9146'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPICIOUS_PERSON', 'Gyanús személy', 'Olyan személy, akinek viselkedése konkrét biztonsági aggályt kelt.', 10, true),
        ('6fbc604d-7544-56a6-a5f5-aa83c62e80f1'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPICIOUS_GROUP', 'Gyanús csoport', 'Olyan csoport, amelynek viselkedése konkrét biztonsági aggályt kelt.', 20, true),
        ('891f2e26-aace-5a57-b850-25f65a17320e'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPICIOUS_PACKAGE', 'Gyanús csomag', 'Tulajdonságai vagy elhelyezése miatt gyanúsnak tűnő csomag.', 30, true),
        ('ca395227-aadc-581e-a205-b82ab8b4a3d9'::uuid, 'SUSPICIOUS_ACTIVITY', 'UNATTENDED_PACKAGE', 'Elhagyott csomag', 'Gazdátlannak vagy felügyelet nélkül hagyottnak tűnő csomag.', 40, true),
        ('d08d41d9-3b12-5752-a5b7-d0e121d3cc0c'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPICIOUS_OBJECT', 'Gyanús tárgy', 'Nem csomag jellegű, biztonsági aggályt keltő ismeretlen tárgy.', 50, true),
        ('877f53b1-6988-53b0-8b75-cb7aba0f89dd'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPECTED_WEAPON', 'Fegyvernek tűnő tárgy', 'Lőfegyvernek, késnek vagy más fegyvernek tűnő tárgy észlelése fenyegetés nélkül.', 60, true),
        ('07383ed1-feb2-5130-9c9c-14ad75768cb5'::uuid, 'SUSPICIOUS_ACTIVITY', 'SUSPECTED_EXPLOSIVE', 'Robbanóanyag vagy robbanószerkezet gyanúja', 'Robbanóanyagra vagy robbanószerkezetre utaló konkrét gyanú.', 70, true),
        ('61ca4e31-6a28-59b3-ad0b-f626a7829a77'::uuid, 'SUSPICIOUS_ACTIVITY', 'TAMPERING_WITH_EQUIPMENT', 'Gyanús beavatkozás vasúti berendezésbe', 'Vasúti vagy vonati berendezés jogosulatlannak tűnő manipulálása.', 80, true),
        ('bcbdf72b-bc37-5900-9188-0904de38cd93'::uuid, 'MEDICAL_WELFARE', 'ILLNESS', 'Rosszullét', 'Olyan rosszullét, amely segítséget vagy személyzeti beavatkozást igényelhet.', 10, true),
        ('702d319a-eb0e-50f8-9b3a-8d516cb7fae0'::uuid, 'MEDICAL_WELFARE', 'UNCONSCIOUS_PERSON', 'Eszméletlen személy', 'Eszméletlen vagy nem reagáló személy észlelése.', 20, true),
        ('5e93942a-f057-5c84-896e-f098cf67b605'::uuid, 'MEDICAL_WELFARE', 'INJURED_PERSON', 'Sérült személy', 'Láthatóan sérült vagy sérülés miatt segítségre szoruló személy.', 30, true),
        ('3b0d521c-fe2b-5965-ad55-1e0390cd0910'::uuid, 'MEDICAL_WELFARE', 'PERSON_NEEDS_HELP', 'Segítségre szoruló személy', 'Olyan személy, aki láthatóan segítségre szorul, de a probléma nem sorolható pontosabban más eseménytípusba.', 40, true),
        ('2b9b4623-a94e-55f2-b71b-c7d28d4b6aa3'::uuid, 'MEDICAL_WELFARE', 'CONFUSED_PERSON', 'Zavart vagy dezorientált személy', 'Zavartnak, tájékozatlannak vagy dezorientáltnak tűnő személy.', 50, true),
        ('990f8aff-f20a-5f9b-8fd8-051c78daf265'::uuid, 'MEDICAL_WELFARE', 'UNACCOMPANIED_CHILD', 'Felügyelet nélkül maradt gyermek', 'Kísérő nélkül vagy veszélyeztetett helyzetben lévő gyermek.', 60, true),
        ('f06b57c3-872d-5b1c-8c48-cd5d5159b647'::uuid, 'MEDICAL_WELFARE', 'POSSIBLE_OVERDOSE', 'Túladagolás gyanúja', 'Gyógyszer vagy szer túladagolására utaló állapot gyanúja.', 70, true),
        ('a28dd568-985e-58b7-b0be-2a6c4b4c18a3'::uuid, 'SAFETY_HAZARD', 'FIRE_OR_SMOKE', 'Tűz vagy füst', 'Tűz, füst vagy égésre utaló jel a vonaton vagy közvetlen környezetében.', 10, true),
        ('e06250d6-320f-5ad4-bd90-a3e2facc046b'::uuid, 'SAFETY_HAZARD', 'DOOR_MALFUNCTION', 'Ajtó meghibásodása', 'Utasbiztonságot érintő ajtóhiba vagy rendellenes működés.', 20, true),
        ('48d8cacc-6ff4-5e3a-b85e-3c96afb6f96d'::uuid, 'SAFETY_HAZARD', 'BROKEN_GLASS_HAZARD', 'Törött üveg vagy sérülésveszély', 'Törött üveg vagy más éles, sérülésveszélyt okozó elem.', 30, true),
        ('fa911fde-e753-5899-af5b-275c04d5af58'::uuid, 'SAFETY_HAZARD', 'CARRIAGE_OBSTRUCTION', 'Közlekedést akadályozó tárgy', 'A kocsiban történő biztonságos közlekedést akadályozó tárgy.', 40, true),
        ('1eaf4529-d064-5dc4-a189-3ce2620da1dd'::uuid, 'SAFETY_HAZARD', 'LIQUID_SPILL', 'Csúszásveszélyes kiömlött folyadék', 'Olyan kiömlött folyadék, amely elcsúszás vagy elesés veszélyét okozza.', 50, true),
        ('e1910d1e-6c8c-5a28-bf8a-fdcad3eafb48'::uuid, 'SAFETY_HAZARD', 'EMERGENCY_EXIT_BLOCKED', 'Vészkijárat akadályozva', 'Vészkijárat vagy menekülési útvonal akadályozása.', 60, true),
        ('48316545-33f0-5eb6-8324-ad9606644748'::uuid, 'SAFETY_HAZARD', 'EMERGENCY_EQUIPMENT_DAMAGED', 'Vészhelyzeti berendezés sérült', 'Vészjelző, vésznyitó vagy más vészhelyzeti berendezés sérülése.', 70, true),
        ('e06ff3dd-8ba3-552a-8ef6-c3aab74987df'::uuid, 'SAFETY_HAZARD', 'DANGEROUS_BEHAVIOR_NEAR_DOOR', 'Veszélyes viselkedés az ajtónál', 'Ajtó, peron vagy ki-/beszállási terület közelében végzett közvetlenül veszélyes magatartás.', 80, true),
        ('487ec720-bb79-5a75-89a7-14abcc88ea1e'::uuid, 'SAFETY_HAZARD', 'PERSON_ON_TRACK_OR_DANGER_ZONE', 'Személy a vágányon vagy veszélyzónában', 'Személy észlelése vágányon vagy más, közvetlen vasúti veszélyzónában.', 90, true),
        ('0542bca3-1cfe-53f3-a4e9-bca528d0fbf5'::uuid, 'SAFETY_HAZARD', 'ELECTRICAL_OR_SPARK_HAZARD', 'Elektromos hiba vagy szikrázás gyanúja', 'Szikrázás, elektromos rendellenesség vagy hasonló veszély észlelése.', 100, true),
        ('2c42e307-ec1b-5a78-b103-b72bd8c4d4a1'::uuid, 'RULE_VIOLATION_OTHER', 'SMOKING', 'Dohányzás', 'Dohányzás a vonaton vagy más tiltott vasúti területen.', 10, true),
        ('8507be76-7225-5f76-894f-63dff8bc7b58'::uuid, 'RULE_VIOLATION_OTHER', 'VAPING', 'Elektromos cigaretta használata', 'E-cigaretta vagy hasonló eszköz használata tiltott helyen.', 20, true),
        ('8d4a452b-a351-5613-ad94-2ca45e7ec397'::uuid, 'RULE_VIOLATION_OTHER', 'LITTERING', 'Szemetelés', 'Hulladék szándékos eldobása vagy jelentős szemetelés.', 30, true),
        ('a09c9cf9-659e-5736-8469-b5139afb8a40'::uuid, 'RULE_VIOLATION_OTHER', 'DOOR_OBSTRUCTION', 'Ajtó szándékos akadályozása', 'Vonatajtó szándékos kitámasztása vagy akadályozása.', 40, true),
        ('f8f00f8e-7d49-58aa-8328-b4751864507a'::uuid, 'RULE_VIOLATION_OTHER', 'AISLE_OBSTRUCTION', 'Átjáró szándékos akadályozása', 'Folyosó vagy átjáró szándékos eltorlaszolása személyekkel vagy tárgyakkal.', 50, true),
        ('9dbd46b5-bd41-5530-8d63-f0c1aafa3eaf'::uuid, 'RULE_VIOLATION_OTHER', 'MISUSE_OF_EMERGENCY_EQUIPMENT', 'Vészhelyzeti berendezés indokolatlan használata', 'Vészjelző, vészfék vagy más vészhelyzeti eszköz indokolatlan működtetése.', 60, true),
        ('f7454933-9080-59fb-9b3d-25db73b86bc1'::uuid, 'RULE_VIOLATION_OTHER', 'OTHER_SAFETY_EVENT', 'Egyéb biztonsági esemény', 'Más felsorolt eseménytípusba nem illő, de biztonsági szempontból releváns esemény.', 70, true)
)
INSERT INTO event_types (id, category_id, code, label, description, sort_order, active)
SELECT s.id, c.id, s.code, s.label, s.description, s.sort_order, s.active
FROM seed s
JOIN event_categories c ON c.code = s.category_code
ON CONFLICT (code) DO UPDATE SET
    category_id = EXCLUDED.category_id,
    label = EXCLUDED.label,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    updated_at = now();
