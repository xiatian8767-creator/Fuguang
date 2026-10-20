precision highp float;
            uniform sampler2D uImageTexture;
            uniform sampler2D uDepthTexture;
            uniform vec2 uParallaxOffset;
            uniform float uParallaxStrength;
            uniform vec2 uDepthTexel;
            varying vec2 vTexCoord;
            void main() {
                // A small overscan leaves room for movement without mirrored borders.
                vec2 uv = (vTexCoord - 0.5) / 1.08 + 0.5;
                vec2 offset = uParallaxOffset * uParallaxStrength;
                // Keep silhouettes conservative: a single photo has no hidden background.
                vec2 radius = abs(offset) * 1.5 + uDepthTexel * 2.0;
                float center = texture2D(uDepthTexture, uv).r;
                float contrast = 0.0;
                contrast = max(contrast, abs(center - texture2D(uDepthTexture, uv + vec2(radius.x, 0.0)).r));
                contrast = max(contrast, abs(center - texture2D(uDepthTexture, uv - vec2(radius.x, 0.0)).r));
                contrast = max(contrast, abs(center - texture2D(uDepthTexture, uv + vec2(0.0, radius.y)).r));
                contrast = max(contrast, abs(center - texture2D(uDepthTexture, uv - vec2(0.0, radius.y)).r));
                offset *= mix(1.0, 0.25, smoothstep(0.08, 0.35, contrast));
                // Trace from the nearest surface instead of sampling output-pixel depth.
                // This avoids sampling the same foreground again across its silhouette.
                float upper = 1.0;
                float lower = 0.0;
                bool hit = false;
                for (int i = 0; i <= 8; i++) {
                    float z = 1.0 - float(i) / 8.0;
                    float sampleDepth = texture2D(uDepthTexture, uv + offset * (z - 0.5)).r;
                    if (!hit) {
                        if (sampleDepth >= z) { lower = z; hit = true; }
                        else { upper = z; }
                    }
                }
                for (int i = 0; i < 3; i++) {
                    float z = (upper + lower) * 0.5;
                    if (texture2D(uDepthTexture, uv + offset * (z - 0.5)).r >= z) lower = z;
                    else upper = z;
                }
                vec2 shifted = uv + offset * ((upper + lower) * 0.5 - 0.5);
                gl_FragColor = texture2D(uImageTexture, clamp(shifted, 0.001, 0.999));
            }
