import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        ink: "#07111f",
        electric: "#6d5dfc",
        lagoon: "#0fd3c7",
      },
      boxShadow: {
        glow: "0 24px 80px rgba(109, 93, 252, 0.28)",
      },
    },
  },
  plugins: [],
};

export default config;
