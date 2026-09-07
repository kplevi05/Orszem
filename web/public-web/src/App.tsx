/**
 * Public Web application shell.
 *
 * Phase 1 is the responsive shell and visual identity only: no report submission, no
 * login (the Public Web is anonymous and has no sign-in at all), no offline queue.
 */
export function App() {
  return (
    <div className="page">
      <header className="header">
        <div className="container header__inner">
          <span className="brand">Őrszem</span>
          <span className="brand__tag">Bejelentő</span>
        </div>
      </header>

      <main className="container main">
        <section className="hero">
          <h1 className="hero__title">Vasúti észlelések bejelentése</h1>
          <p className="hero__lead">
            Az Őrszem lehetővé teszi, hogy a vasúti környezetben tapasztalt eseményeket
            gyorsan és egyszerűen jelezni lehessen az illetékes szolgálat felé.
          </p>
          <p className="notice" role="status">
            Az alkalmazás fejlesztés alatt áll. A bejelentési folyamat még nem érhető el.
          </p>
        </section>

        <section className="cards">
          <article className="card">
            <h2 className="card__title">Egyszerű</h2>
            <p className="card__body">
              Néhány lépésben, felesleges kérdések nélkül lehet majd bejelentést tenni.
            </p>
          </article>
          <article className="card">
            <h2 className="card__title">Névtelen</h2>
            <p className="card__body">
              A nyilvános bejelentéshez nem kell fiókot létrehozni és nem kell bejelentkezni.
            </p>
          </article>
          <article className="card">
            <h2 className="card__title">Mobilon is</h2>
            <p className="card__body">
              A webes felület mellett Android alkalmazás is készül ugyanehhez a szolgáltatáshoz.
            </p>
          </article>
        </section>
      </main>

      <footer className="footer">
        <div className="container">
          <p className="footer__text">Őrszem · orszembejelento.hu</p>
        </div>
      </footer>
    </div>
  )
}
