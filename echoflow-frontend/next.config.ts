import type { NextConfig } from "next";

const isStaticExport = process.env.STATIC_EXPORT === "true";

const nextConfig: NextConfig = {
  ...(isStaticExport && { output: "export" }),
  ...(!isStaticExport && {
    async rewrites() {
      return [
        {
          source: "/api/:path*",
          destination: `${process.env.BACKEND_URL ?? "http://localhost:8080"}/api/:path*`,
        },
      ];
    },
  }),
};

export default nextConfig;
