import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  devIndicators: false,
  async redirects() {
    // The Operations Overview page is now Home (masterplan D14). Old bookmarks keep working.
    return [{ source: "/overview", destination: "/home", permanent: false }];
  },
};

export default nextConfig;
