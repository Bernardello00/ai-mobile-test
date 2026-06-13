import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI Mobile Lab",
  description: "Laboratorio mobile-first per prototipare esperienze AI pronte per il mercato.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="it">
      <body>{children}</body>
    </html>
  );
}
