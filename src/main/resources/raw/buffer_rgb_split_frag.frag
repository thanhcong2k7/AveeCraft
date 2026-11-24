
precision mediump float;
precision mediump int;
precision lowp sampler2D;
precision lowp samplerCube;

//"in" attributes from our vertex shader
//varying vec4 vColor;
varying vec2 vTexCoord;

//declare uniforms
uniform sampler2D u_texture;

uniform sampler2D u_texture2;

uniform vec2 dirAmount;

uniform vec4 splitColor0;
uniform vec4 splitColor1;
uniform vec4 splitColor2;

void main() {

    //vec2 vTexCoordOffset = vec2(0.003, 0.003);

	vec4 color0 = texture2D(u_texture,vTexCoord+dirAmount);
	vec4 color1 = texture2D(u_texture,vTexCoord);
	vec4 color2 = texture2D(u_texture,vTexCoord-dirAmount);
	//vec4 color2 = texture2D(u_texture2,vTexCoord);

//color1.r = color0.r;
//color1.g = 0.0;
//color1.b = color2.b;

vec4 finalColor;

finalColor.r = (color0.r * splitColor0.r) + (color1.r * splitColor1.r) + (color2.r * splitColor2.r);
finalColor.g = (color0.g * splitColor0.g) + (color1.g * splitColor1.g) + (color2.g * splitColor2.g);
finalColor.b = (color0.b * splitColor0.b) + (color1.b * splitColor1.b) + (color2.b * splitColor2.b);

// Black/dark pixels with alpha > 0 have zero RGB channel energy, so a naive multiply keeps
// shifted fringes solid black over transparent/dark regions. Lift only non-overlapping dark
// fringes along each splitColor while keeping shared opaque black cores and opaque backgrounds intact.
float b0 = max(0.0, color0.a - max(color0.r, max(color0.g, color0.b)));
float b1 = max(0.0, color1.a - max(color1.r, max(color1.g, color1.b)));
float b2 = max(0.0, color2.a - max(color2.r, max(color2.g, color2.b)));
float commonBlack = min(b0, min(b1, b2));
float edgeMask = 1.0 - min(color0.a, min(color1.a, color2.a));
finalColor.rgb += (splitColor0.rgb * splitColor0.a * max(0.0, b0 - commonBlack)
                 + splitColor1.rgb * splitColor1.a * max(0.0, b1 - commonBlack)
                 + splitColor2.rgb * splitColor2.a * max(0.0, b2 - commonBlack)) * edgeMask;

float maskmul = 1.0/3.0;

finalColor.a = (color0.a * splitColor0.a + color1.a * splitColor1.a + color2.a * splitColor2.a);

	//color1 = vColor * color1;
	//color1.a = color1.a * (maskadd + (color2.a * maskmul));

	//color1.a = color1.a * color2.a;
	gl_FragColor = clamp(finalColor, 0.0, 1.0);
	//gl_FragColor = vec4(1.0, 0.0, 0.0 ,1.0);
}