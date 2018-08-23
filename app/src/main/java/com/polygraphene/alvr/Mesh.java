package com.polygraphene.alvr;


import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import com.google.vr.sdk.base.Eye;
import java.nio.FloatBuffer;

import static com.polygraphene.alvr.Utils.checkGlError;

/**
 * Utility class to generate & render spherical meshes for video or images. Use the static creation
 * methods to construct the Mesh's data. Then call the Mesh constructor on the GL thread when ready.
 * Use glDraw method to render it.
 */
public final class Mesh {
    // Basic vertex & fragment shaders to render a mesh with 3D position & 2D texture data.
    private static final String[] VERTEX_SHADER_CODE =
        new String[] {
            "uniform mat4 uMvpMatrix;",
            "attribute vec4 aPosition;",
            "attribute vec2 aTexCoords;",
            "varying vec2 vTexCoords;",

            // Standard transformation.
            "void main() {",
            "  gl_Position = uMvpMatrix * aPosition;",
            "  vTexCoords = aTexCoords;",
            "}"
        };
    private static final String[] FRAGMENT_SHADER_CODE =
        new String[] {
            // This is required since the texture data is GL_TEXTURE_EXTERNAL_OES.
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",

            // Standard texture rendering shader.
            "uniform samplerExternalOES uTexture;",
            "varying vec2 vTexCoords;",
            "void main() {",
            "  gl_FragColor = texture2D(uTexture, vTexCoords);",
            "}"
        };

    // Constants related to vertex data.
    private static final int POSITION_COORDS_PER_VERTEX = 3; // X, Y, Z.
    private static final int TEXTURE_COORDS_PER_VERTEX = 2;
    // COORDS_PER_VERTEX
    private static final int CPV = POSITION_COORDS_PER_VERTEX + TEXTURE_COORDS_PER_VERTEX;
    // Data is tightly packed. Each vertex is [x, y, z, u_left, v_left, u_right, v_right].
    private static final int VERTEX_STRIDE_BYTES = CPV * Utils.BYTES_PER_FLOAT;

    // Vertices for the mesh with 3D position + left 2D texture UV + right 2D texture UV.
    public final float[] vertices;
    private final FloatBuffer vertexBuffer;

    // Program related GL items. These are only valid if program != 0.
    private int program;
    private int mvpMatrixHandle;
    private int positionHandle;
    private int texCoordsHandle;
    private int textureHandle;
    private int textureId;


    public static Mesh createLrStereoQuad(float w, float h, float z) {
        float[] data = new float[] {
            -w / 2, -h / 2, z, 0, 1,
                 w, -h / 2, z, 1, 1,
            -w / 2,      h, z, 0, 0,
                 w,      h, z, 1, 0
        };
        return new Mesh(data);
    }

    /** Used by static constructors. */
    private Mesh(float[] vertexData) {
        vertices = vertexData;
        vertexBuffer = Utils.createBuffer(vertices);
    }

    /**
     * Finishes initialization of the GL components.
     *
     * @param textureId GL_TEXTURE_EXTERNAL_OES used for this mesh.
     */
    /* package */ void glInit(int textureId) {
        this.textureId = textureId;

        program = Utils.compileProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE);

        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix");
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordsHandle = GLES20.glGetAttribLocation(program, "aTexCoords");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
    }

    /**
     * Renders the mesh. This must be called on the GL thread.
     *
     * @param mvpMatrix The Model View Projection matrix.
     */
    /* package */ void glDraw(float[] mvpMatrix) {
        // Configure shader.
        checkGlError();
        GLES20.glUseProgram(program);
        checkGlError();

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(texCoordsHandle);
        checkGlError();

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureHandle, 0);
        checkGlError();

        // Load position data.
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(
            positionHandle,
            POSITION_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer);
        checkGlError();

        // Load texture data.
        vertexBuffer.position(POSITION_COORDS_PER_VERTEX);
        GLES20.glVertexAttribPointer(
            texCoordsHandle,
            TEXTURE_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer);
        checkGlError();

        // Render.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertices.length / CPV);
        checkGlError();

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordsHandle);
    }

    /** Cleans up the GL resources. */
    /* package */ void glShutdown() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        }
    }
}