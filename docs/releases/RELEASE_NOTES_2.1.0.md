# Őrszem V2 — 2.1.0 kiadási jegyzet

**Állapot: kiadásra kész.**

Alap: `v2.0.10`. Előző szerveroldali kiadás: `v2.0.9`.

## Főbb változások és fejlesztések

1. **Felhasználói szűrők az Őrszem Szolgálat admin felületén (`UsersScreen`):**
   - **Státusz szűrő:** Lehetőség nyílt a felhasználók szűrésére aktív és inaktív állapot szerint (`Aktív`, `Deaktivált`, `Összes`).
   - **Alapértelmezett nézet:** Megnyitáskor automatikusan a minden aktív felhasználót tartalmazó lista jelenik meg.
   - **Szolgálati terület szerinti szűrés:** A rendszer dinamikusan betölti a hozzárendelhető szolgálati területeket, és egy vízszintesen görgethető szűrősávban (`FilterChip`) biztosítja a kiválasztást (`Minden terület`, illetve területenként külön-külön).

2. **Szerepkör-módosító gombok pontosítása (`UserDetailScreen`):**
   - A korábbi kétértelmű gombfeliratok helyett egyértelmű műveletleíró szövegezés került bevezetésre:
     - `Szerepkör: Moderátor` $\rightarrow$ `Új szerepkör beállítása: Moderátor`
     - `Szerepkör: Szolgálati munkatárs` $\rightarrow$ `Új szerepkör beállítása: Szolgálati munkatárs`

3. **Vasútvonal-választó elrejtése a bejelentés kezdeti lépésénél:**
   - A Public Android app (`public-app`) és a Public Web (`public-web`) felületén a bejelentés 1. lépésében elrejtésre került a vasútvonal választás.
   - A település kiválasztása után a felhasználó azonnal továbbhaladhat a 2. lépésre (kategória és eseménytípus kiválasztása), megkönnyítve és felgyorsítva a bejelentés folyamatát.

4. **Automatikus In-App frissítés a korábbi 2.0.10-es verzióról:**
   - A v2.1.0 verzió az első olyan kiadás, amelyet a 2.0.10-es verzióval rendelkező szolgálati felhasználók közvetlenül az alkalmazáson belülről, egyetlen érintéssel feltelepíthetnek.

## Verziók és komponensek

| Komponens | Verzió | Megjegyzés |
|---|---:|---|
| Service Android | 2.0.10 (11) → **2.1.0 (12)** | Felhasználói szűrők, szerepkör gombok |
| Public Android | 2.0.9 (10) → **2.1.0 (12)** | Vasútvonal választás elrejtése 1. lépésben |
| Backend | 2.0.9 → **2.1.0** | Verziószám frissítés, Flyway séma változatlan (V010) |
| Public Web | 2.0.9 → **2.1.0** | Vasútvonal választás elrejtése 1. lépésben |
