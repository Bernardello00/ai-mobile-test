const creationCards = [
  "Assistenti vocali per onboarding e supporto",
  "Copilot per field service e vendite in mobilità",
  "Generatori di contenuti social e schede prodotto",
  "Dashboard predittive ottimizzate per touch e notifiche",
];

const steps = [
  "Definisci il caso d'uso e i dati disponibili",
  "Prototipa conversazioni, flussi e interfacce responsive",
  "Valida su smartphone con metriche e feedback reali",
];

export default function Home() {
  return (
    <main className="min-h-screen overflow-hidden">
      <section className="mx-auto flex w-full max-w-7xl flex-col gap-16 px-6 py-8 sm:px-8 lg:px-12">
        <nav className="flex items-center justify-between rounded-full border border-white/10 bg-white/5 px-5 py-3 backdrop-blur">
          <span className="text-sm font-semibold uppercase tracking-[0.3em] text-lagoon">AI Mobile Lab</span>
          <a className="rounded-full bg-white px-4 py-2 text-sm font-bold text-ink transition hover:bg-lagoon" href="#workflow">
            Avvia il lab
          </a>
        </nav>

        <div className="grid items-center gap-12 lg:grid-cols-[1.1fr_0.9fr]">
          <div className="space-y-8">
            <div className="inline-flex rounded-full border border-lagoon/40 bg-lagoon/10 px-4 py-2 text-sm text-lagoon">
              Prototipi AI pensati prima per smartphone
            </div>
            <div className="space-y-5">
              <h1 className="max-w-4xl text-5xl font-black tracking-tight sm:text-6xl lg:text-7xl">
                Trasforma idee AI in esperienze mobile pronte da testare.
              </h1>
              <p className="max-w-2xl text-lg leading-8 text-slate-300 sm:text-xl">
                AI Mobile Lab combina strategia, design responsive e componenti intelligenti per accelerare MVP, demo e workflow operativi ottimizzati per utenti in movimento.
              </p>
            </div>
            <div className="flex flex-col gap-3 sm:flex-row">
              <a className="rounded-2xl bg-electric px-6 py-4 text-center font-bold shadow-glow transition hover:-translate-y-1" href="#come-funziona">
                Scopri come funziona
              </a>
              <a className="rounded-2xl border border-white/15 px-6 py-4 text-center font-bold text-white transition hover:border-lagoon hover:text-lagoon" href="#cosa-creare">
                Esplora i casi d'uso
              </a>
            </div>
          </div>

          <div className="relative mx-auto w-full max-w-sm rounded-[3rem] border border-white/10 bg-white/10 p-4 shadow-glow backdrop-blur">
            <div className="rounded-[2.5rem] bg-ink p-5">
              <div className="mx-auto mb-6 h-1.5 w-24 rounded-full bg-white/20" />
              <div className="space-y-4">
                {steps.map((step, index) => (
                  <div className="rounded-3xl border border-white/10 bg-white/5 p-4" key={step}>
                    <span className="text-xs font-bold text-lagoon">STEP {index + 1}</span>
                    <p className="mt-2 text-sm text-slate-200">{step}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="mx-auto grid max-w-7xl gap-6 px-6 py-16 sm:px-8 md:grid-cols-3 lg:px-12" id="come-funziona">
        {steps.map((step, index) => (
          <article className="rounded-3xl border border-white/10 bg-white/[0.06] p-6" key={step}>
            <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-lagoon/15 text-xl font-black text-lagoon">{index + 1}</div>
            <h2 className="text-2xl font-bold">Come funziona</h2>
            <p className="mt-3 text-slate-300">{step} con sprint rapidi, test continui e decisioni guidate da dati.</p>
          </article>
        ))}
      </section>

      <section className="mx-auto max-w-7xl px-6 py-16 sm:px-8 lg:px-12" id="cosa-creare">
        <div className="mb-10 max-w-2xl">
          <p className="font-bold uppercase tracking-[0.25em] text-lagoon">Cosa puoi creare</p>
          <h2 className="mt-3 text-4xl font-black sm:text-5xl">Dalla demo al prodotto, senza perdere il focus mobile.</h2>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {creationCards.map((card) => (
            <article className="min-h-48 rounded-3xl border border-white/10 bg-white p-6 text-ink" key={card}>
              <h3 className="text-xl font-black">{card}</h3>
              <p className="mt-4 text-sm leading-6 text-slate-700">Template, prompt e interazioni progettati per schermi piccoli e task ad alta frequenza.</p>
            </article>
          ))}
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-6 py-16 sm:px-8 lg:px-12" id="workflow">
        <div className="rounded-[2rem] border border-white/10 bg-gradient-to-br from-electric/35 to-lagoon/20 p-8 sm:p-12">
          <p className="font-bold uppercase tracking-[0.25em] text-lagoon">Workflow mobile-first</p>
          <h2 className="mt-4 max-w-3xl text-4xl font-black sm:text-5xl">Priorità a gesture, velocità, accessibilità e contesto d'uso reale.</h2>
          <div className="mt-8 grid gap-4 md:grid-cols-3">
            {["Wireframe touch-first", "Prompt e automazioni", "Build, misura, itera"].map((item) => (
              <div className="rounded-2xl bg-ink/70 p-5" key={item}>
                <h3 className="font-bold text-white">{item}</h3>
                <p className="mt-2 text-sm leading-6 text-slate-300">Ogni decisione viene verificata su viewport mobile prima di scalare verso tablet e desktop.</p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </main>
  );
}
