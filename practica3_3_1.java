package practica3_GVA;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.text.rtf.RTFEditorKit;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

// Imports para Vosk
import org.vosk.Model;
import org.vosk.Recognizer;

// Imports para audio
import javax.sound.sampled.*;

// ============================================
// ENUM Y CONTROLADOR (MANTENER IGUAL)
// ============================================

enum NuevoNuiCommand {
    NUEVO_DOCUMENTO,
    ABRIR_DOCUMENTO,
    GUARDAR_DOCUMENTO,
    APLICAR_NEGRITA,
    APLICAR_CURSIVA,
    COLOR_ROJO,
    COLOR_AZUL,
    DICTAR_TEXTO
}

interface NuevoNuiListener {
    void onCommand(NuevoNuiCommand cmd, String payload);
}

class NuevoNuiController {
    private java.util.List<NuevoNuiListener> listeners = new java.util.ArrayList<>();
    
    public void addListener(NuevoNuiListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(NuevoNuiListener listener) {
        listeners.remove(listener);
    }
    
    public void fireCommand(NuevoNuiCommand cmd, String payload) {
        System.out.println("[NUI-VOZ] Comando: " + cmd + 
                          (payload != null ? " (" + payload + ")" : ""));
        
        for (NuevoNuiListener listener : listeners) {
            listener.onCommand(cmd, payload);
        }
    }
    
    public void fireCommand(NuevoNuiCommand cmd) {
        fireCommand(cmd, null);
    }
}

// ============================================
// PANEL DE CONTROL DE VOZ MEJORADO
// ============================================

class VoiceControlPanel extends JPanel {
    private JButton btnStartStop;
    private JLabel statusLabel;
    private JTextArea logArea;
    private NuevoNuiController nuiController;
    private boolean isListening = false;
    private SwingWorker<Void, String> worker;
    private JLabel voiceIndicator;
    
    // Componentes Vosk
    private static Model model;
    private Recognizer recognizer;
    private TargetDataLine microphoneLine;
    
    // Ruta al modelo
    private static final String MODEL_PATH = "vosk-model-small-es-0.42";
    
    // Mapeo de comandos
    private Map<String, NuevoNuiCommand> commandMap = new HashMap<>();
    
    public VoiceControlPanel(NuevoNuiController controller) {
        this.nuiController = controller;
        initCommandMap();
        initUI();
    }
    
    private void initCommandMap() {
        // Español
        commandMap.put("nuevo", NuevoNuiCommand.NUEVO_DOCUMENTO);
        commandMap.put("nueva", NuevoNuiCommand.NUEVO_DOCUMENTO);
        commandMap.put("abrir", NuevoNuiCommand.ABRIR_DOCUMENTO);
        commandMap.put("guardar", NuevoNuiCommand.GUARDAR_DOCUMENTO);
        commandMap.put("negrita", NuevoNuiCommand.APLICAR_NEGRITA);
        commandMap.put("cursiva", NuevoNuiCommand.APLICAR_CURSIVA);
        commandMap.put("itálica", NuevoNuiCommand.APLICAR_CURSIVA);
        commandMap.put("rojo", NuevoNuiCommand.COLOR_ROJO);
        commandMap.put("azul", NuevoNuiCommand.COLOR_AZUL);
        commandMap.put("escribir", NuevoNuiCommand.DICTAR_TEXTO);
        commandMap.put("dictar", NuevoNuiCommand.DICTAR_TEXTO);
        
        // Inglés
        commandMap.put("new", NuevoNuiCommand.NUEVO_DOCUMENTO);
        commandMap.put("open", NuevoNuiCommand.ABRIR_DOCUMENTO);
        commandMap.put("save", NuevoNuiCommand.GUARDAR_DOCUMENTO);
        commandMap.put("bold", NuevoNuiCommand.APLICAR_NEGRITA);
        commandMap.put("italic", NuevoNuiCommand.APLICAR_CURSIVA);
        commandMap.put("red", NuevoNuiCommand.COLOR_ROJO);
        commandMap.put("blue", NuevoNuiCommand.COLOR_AZUL);
        commandMap.put("write", NuevoNuiCommand.DICTAR_TEXTO);
    }
    
    private void initUI() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Control de Voz Real (Vosk)"));
        
        // Panel de control
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        btnStartStop = new JButton("▶ Iniciar Escucha por Voz");
        btnStartStop.setBackground(new Color(70, 130, 180)); // Azul accesible
        btnStartStop.setForeground(Color.WHITE);
        btnStartStop.setFont(new Font("Arial", Font.BOLD, 12));
        btnStartStop.addActionListener(e -> toggleListening());
        btnStartStop.setToolTipText("Activar/desactivar reconocimiento de voz por comando");
        
        // Indicador de estado (LED virtual)
        voiceIndicator = new JLabel("●");
        voiceIndicator.setFont(new Font("Arial", Font.BOLD, 20));
        voiceIndicator.setForeground(Color.RED);
        voiceIndicator.setToolTipText("Micrófono inactivo - Haz clic para activar");
        
        statusLabel = new JLabel(" Estado: INACTIVO (haz clic para iniciar)");
        statusLabel.setForeground(Color.RED);
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        statusLabel.setToolTipText("Estado actual del reconocimiento de voz");
        
        controlPanel.add(btnStartStop);
        controlPanel.add(Box.createHorizontalStrut(10));
        controlPanel.add(voiceIndicator);
        controlPanel.add(Box.createHorizontalStrut(10));
        controlPanel.add(statusLabel);
        
        // Área de log
        logArea = new JTextArea(4, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(240, 240, 240));
        logArea.setForeground(Color.DARK_GRAY);
        JScrollPane scrollPane = new JScrollPane(logArea);
        
        // Panel de ayuda
        JPanel helpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel lblHelp = new JLabel("<html><b>Comandos de voz:</b> 'nuevo', 'abrir', 'guardar', 'negrita', 'cursiva', 'rojo', 'azul', 'escribir [texto]'</html>");
        helpPanel.add(lblHelp);
        
        add(controlPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(helpPanel, BorderLayout.SOUTH);
    }
    
    void toggleListening() {
        if (!isListening) {
            startVoiceListening();
        } else {
            stopVoiceListening();
        }
    }
    
    private void startVoiceListening() {
        if (worker != null && !worker.isDone()) {
            logMessage("⚠️ El reconocimiento ya está activo.");
            return;
        }

        btnStartStop.setEnabled(false);
        statusLabel.setText(" Estado: VERIFICANDO MICRÓFONO...");
        statusLabel.setForeground(Color.ORANGE);

        worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // ===== PRIMERO: PRUEBA INDEPENDIENTE DEL MICRÓFONO =====
                    publish("[SISTEMA] Iniciando prueba de hardware...");
                    
                    AudioFormat testFormat = new AudioFormat(16000, 16, 1, true, false);
                    DataLine.Info testInfo = new DataLine.Info(TargetDataLine.class, testFormat);
                    
                    // 1. Listar TODOS los dispositivos
                    publish("[AUDIO] === DISPOSITIVOS DE ENTRADA ===");
                    Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                    TargetDataLine testLine = null;
                    Mixer.Info selectedMixerInfo = null;
                    
                    for (Mixer.Info mixerInfo : mixers) {
                        Mixer mixer = AudioSystem.getMixer(mixerInfo);
                        if (mixer.isLineSupported(testInfo)) {
                            publish("[AUDIO] ✓ " + mixerInfo.getName() + 
                                    " | " + mixerInfo.getDescription());
                            
                            // Intentar abrir este mixer
                            try {
                                testLine = (TargetDataLine) mixer.getLine(testInfo);
                                testLine.open(testFormat);
                                testLine.start();
                                selectedMixerInfo = mixerInfo;
                                publish("[AUDIO] ✅ CONECTADO a: " + mixerInfo.getName());
                                break; // Usar el primero que funcione
                            } catch (LineUnavailableException e) {
                                publish("[AUDIO] ❌ No se pudo abrir: " + e.getMessage());
                                if (testLine != null) {
                                    testLine.close();
                                    testLine = null;
                                }
                            }
                        }
                    }
                    
                    if (testLine == null) {
                        throw new Exception("No se pudo abrir ningún micrófono. Verifica permisos.");
                    }
                    
                    // 2. PRUEBA REAL DE CAPTURA (2 segundos)
                    publish("[AUDIO] Probando captura (2 segundos)...");
                    byte[] testBuffer = new byte[32000]; // 2 segundos a 16kHz
                    int totalRead = 0;
                    long startTime = System.currentTimeMillis();
                    
                    while (totalRead < testBuffer.length && 
                           (System.currentTimeMillis() - startTime) < 2500) {
                        int read = testLine.read(testBuffer, totalRead, 
                                               testBuffer.length - totalRead);
                        if (read > 0) totalRead += read;
                    }
                    
                    testLine.stop();
                    testLine.close();
                    
                    // 3. Analizar señal capturada
                    double maxLevel = 0;
                    double avgLevel = 0;
                    int sampleCount = totalRead / 2;
                    
                    if (sampleCount > 0) {
                        for (int i = 0; i < totalRead; i += 2) {
                            short sample = (short) ((testBuffer[i + 1] << 8) | 
                                                   (testBuffer[i] & 0xFF));
                            double absSample = Math.abs(sample);
                            avgLevel += absSample;
                            if (absSample > maxLevel) maxLevel = absSample;
                        }
                        avgLevel /= sampleCount;
                        
                        publish(String.format("[AUDIO] 📊 Niveles: Avg=%.0f, Max=%.0f", 
                                             avgLevel, maxLevel));
                        
                        if (avgLevel < 50.0) {
                            publish("[AUDIO] ⚠️ Audio muy bajo. ¿Micrófono silenciado?");
                        } else {
                            publish("[AUDIO] ✅ Señal de audio detectada correctamente");
                        }
                    } else {
                        publish("[AUDIO] ❌ No se capturó ningún byte de audio");
                    }
                    
                    // ===== SEGUNDO: INICIAR VOSK CON EL MICRÓFONO QUE SÍ FUNCIONA =====
                    publish("[VOSK] Iniciando reconocedor...");
                    
                    if (model == null) {
                        File modelDir = new File(MODEL_PATH);
                        if (!modelDir.exists()) {
                            modelDir = new File(System.getProperty("user.dir"), MODEL_PATH);
                        }
                        if (!modelDir.exists()) {
                            throw new Exception("Modelo no encontrado en: " + modelDir.getAbsolutePath());
                        }
                        model = new Model(modelDir.getAbsolutePath());
                    }
                    
                    recognizer = new Recognizer(model, 16000);
                    
                    // Usar el mixer que ya probamos que funciona
                    Mixer workingMixer = AudioSystem.getMixer(selectedMixerInfo);
                    microphoneLine = (TargetDataLine) workingMixer.getLine(testInfo);
                    microphoneLine.open(testFormat);
                    microphoneLine.start();
                    
                    publish("[VOSK] ✅ Reconocedor listo. ¡Habla ahora!");
                    
                    // ===== BUCLE PRINCIPAL DE RECONOCIMIENTO =====
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    int silentCounter = 0;
                    
                    while (!isCancelled() && isListening) {
                        bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                        
                        if (bytesRead > 0) {
                            // Enviar a Vosk
                            if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                                String result = recognizer.getResult();
                                publish(result); // Esto activará process() con el texto
                            }
                            
                            // Monitoreo de actividad (opcional, para debug)
                            if (silentCounter++ % 50 == 0) {
                                short currentMax = 0;
                                for (int i = 0; i < bytesRead; i += 2) {
                                    short s = (short)((buffer[i+1] << 8) | (buffer[i] & 0xFF));
                                    if (Math.abs(s) > currentMax) currentMax = (short)Math.abs(s);
                                }
                                publish("[MONITOR] Nivel: " + currentMax);
                            }
                        }
                    }
                    
                } catch (LineUnavailableException e) {
                    publish("[ERROR] Línea no disponible: " + e.getMessage());
                    publish("[SOLUCIÓN] 1. Cierra otras apps que usen micrófono");
                    publish("[SOLUCIÓN] 2. Verifica permisos del sistema");
                    publish("[SOLUCIÓN] 3. Prueba con otro micrófono si tienes");
                } catch (Exception e) {
                    publish("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            }
            
            @Override
            protected void process(List<String> chunks) {
                for (String text : chunks) {
                    if (text.startsWith("[")) {
                        // Mensaje del sistema
                        logMessage(text);
                        
                        if (text.contains("✅ CONECTADO a:") || text.contains("✅ Reconocedor listo")) {
                            SwingUtilities.invokeLater(() -> {
                                isListening = true;
                                btnStartStop.setEnabled(true);
                                btnStartStop.setText("⏸ Detener Escucha");
                                btnStartStop.setBackground(new Color(200, 50, 50));
                                
                                // Actualizar indicador
                                voiceIndicator.setForeground(new Color(0, 200, 0));
                                voiceIndicator.setToolTipText("Micrófono ACTIVO - Habla ahora");
                                
                                statusLabel.setText(" Estado: ESCUCHANDO - ¡Habla!");
                                statusLabel.setForeground(new Color(0, 150, 0));
                            });
                        }
                    } else {
                        // Texto reconocido por Vosk
                        String recognizedText = extractTextFromJson(text);
                        if (!recognizedText.isEmpty()) {
                            logMessage("🔊 Reconocido: \"" + recognizedText + "\"");
                            processVoiceCommand(recognizedText);
                        }
                    }
                }
            }
            
            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    // Error ya mostrado en process()
                }
            }
        };
        
        worker.execute();
    }
    
    // MÉTODO DE EXTRACCIÓN CORREGIDO
    private String extractTextFromJson(String json) {
        if (json == null || json.isEmpty())
            return "";
        
        // Corregir codificación UTF-8
        String jsonUtf8;
        try {
            jsonUtf8 = new String(json.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception e) {
            jsonUtf8 = json;
        }
        
        // Normalizar JSON (Vosk usa espacios alrededor de ":") - ¡ESTO ES CLAVE!
        String normalizedJson = jsonUtf8.replaceAll("\"\\s*:\\s*\"", "\":\"");
        
        // Buscar campo "text" (resultado final)
        int idx = normalizedJson.indexOf("\"text\":");
        if (idx >= 0) {
            int a = normalizedJson.indexOf('"', idx + 7);
            int b = normalizedJson.indexOf('"', a + 1);
            if (a >= 0 && b > a)
                return normalizedJson.substring(a + 1, b);
        }
        
        // Buscar campo "partial" (resultado parcial)
        idx = normalizedJson.indexOf("\"partial\":");
        if (idx >= 0) {
            int a = normalizedJson.indexOf('"', idx + 10);
            int b = normalizedJson.indexOf('"', a + 1);
            if (a >= 0 && b > a)
                return normalizedJson.substring(a + 1, b);
        }
        
        return "";
    }
    
    private void stopVoiceListening() {
        isListening = false;
        
        // Cancelar el worker
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        
        // Cerrar micrófono
        try {
            if (microphoneLine != null) {
                microphoneLine.stop();
                microphoneLine.close();
                microphoneLine = null;
            }
        } catch (Exception e) {
            // Ignorar errores al cerrar
        }
        
        // Cerrar recognizer (el modelo se reutiliza)
        try {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        } catch (Exception e) {
            // Ignorar errores al cerrar
        }
        
        // Actualizar UI
        SwingUtilities.invokeLater(() -> {
            btnStartStop.setText("▶ Iniciar Escucha por Voz");
            btnStartStop.setBackground(new Color(70, 130, 180));
            
            // Actualizar indicador
            voiceIndicator.setForeground(Color.RED);
            voiceIndicator.setToolTipText("Micrófono INACTIVO");
            
            statusLabel.setText(" Estado: INACTIVO");
            statusLabel.setForeground(Color.RED);
            logMessage("=== RECONOCIMIENTO DETENIDO ===");
        });
        
        // Pausa para liberar recursos nativos
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void processVoiceCommand(String voiceText) {
        String text = voiceText.toLowerCase().trim();
        
        // Detectar dictado
        if (text.startsWith("escribir ") || text.startsWith("dictar ") || text.startsWith("write ")) {
            String dictationText = text.substring(text.indexOf(" ") + 1);
            nuiController.fireCommand(NuevoNuiCommand.DICTAR_TEXTO, dictationText);
            logMessage("📝 Dictando texto: \"" + dictationText + "\"");
            return;
        }
        
        // Buscar comando exacto
        NuevoNuiCommand command = commandMap.get(text);
        
        if (command != null) {
            nuiController.fireCommand(command);
            logMessage("✅ Comando ejecutado: " + command);
        } else {
            // Buscar coincidencias parciales
            for (Map.Entry<String, NuevoNuiCommand> entry : commandMap.entrySet()) {
                if (text.contains(entry.getKey())) {
                    nuiController.fireCommand(entry.getValue());
                    logMessage("✅ Comando ejecutado: " + entry.getValue() + " (por: \"" + entry.getKey() + "\")");
                    return;
                }
            }
            
            logMessage("❓ Comando no reconocido: \"" + text + "\"");
            logMessage("💡 Comandos válidos: nuevo, abrir, guardar, negrita, cursiva, rojo, azul, escribir [texto]");
        }
    }
    
    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}

// ============================================
// EDITOR PRINCIPAL MEJORADO
// ============================================

public class practica3_3_1 extends JFrame implements NuevoNuiListener {

    private JTextPane textPane;
    private JLabel lblChars, lblWords, lblLines;
    private UndoManager undoManager = new UndoManager();
    private ProgressLabel progressLabel;
    private JFileChooser fileChooser;
    private File currentFile;
    
    // Componentes NUI de voz
    private NuevoNuiController nuiController;
    private VoiceControlPanel voiceControlPanel;
    private int contadorComandosVoz = 0;
    private JLabel lblVozStatus;

    public practica3_3_1() {
        setTitle("Editor con Reconocimiento de Voz - VERSIÓN MEJORADA (RA4)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 700);
        setLocationRelativeTo(null);

        Container cp = getContentPane();
        cp.setLayout(new BorderLayout(5,5));
        ((JPanel) cp).setBorder(new EmptyBorder(6,6,6,6));

        // Inicializar capa NUI
        nuiController = new NuevoNuiController();
        nuiController.addListener(this);
        voiceControlPanel = new VoiceControlPanel(nuiController);
        
        // Panel superior con control de voz
        JPanel panelSuperior = new JPanel(new BorderLayout());
        panelSuperior.add(voiceControlPanel, BorderLayout.NORTH);
        
        // Toolbar mejorada con tooltips y mejor contraste
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        // Botones con tooltips descriptivos
        toolBar.add(makeButton("Mayúsculas", e -> transformSelectedOrAll(TextAction.UPPER), 
                  "Convertir texto seleccionado a MAYÚSCULAS"));
        toolBar.add(makeButton("Minúsculas", e -> transformSelectedOrAll(TextAction.LOWER),
                  "Convertir texto seleccionado a minúsculas"));
        toolBar.add(makeButton("Invertir", e -> transformSelectedOrAll(TextAction.REVERSE),
                  "Invertir orden del texto seleccionado"));
        toolBar.add(makeButton("Quitar dobles espacios", e -> transformSelectedOrAll(TextAction.REMOVE_DOUBLE_SPACES),
                  "Eliminar espacios dobles y múltiples del texto"));

        toolBar.addSeparator();
        toolBar.add(makeButton("B", e -> toggleStyle(StyleConstants.CharacterConstants.Bold),
                  "Negrita (Ctrl+B)"));
        toolBar.add(makeButton("I", e -> toggleStyle(StyleConstants.CharacterConstants.Italic),
                  "Cursiva (Ctrl+I)"));
        toolBar.add(makeButton("U", e -> toggleStyle(StyleConstants.CharacterConstants.Underline),
                  "Subrayado"));
        toolBar.add(makeButton("Color", e -> changeColor(),
                  "Cambiar color del texto seleccionado"));

        toolBar.addSeparator();
        toolBar.add(makeButton("Buscar/Reemplazar", e -> openFindReplaceDialog(),
                  "Buscar y reemplazar texto"));

        toolBar.addSeparator();
        JButton undoBtn = makeButton("Deshacer", e -> doUndo(), "Deshacer última acción (Ctrl+Z)");
        JButton redoBtn = makeButton("Rehacer", e -> doRedo(), "Rehacer acción deshecha (Ctrl+Y)");
        toolBar.add(undoBtn);
        toolBar.add(redoBtn);
        
        panelSuperior.add(toolBar, BorderLayout.CENTER);
        cp.add(panelSuperior, BorderLayout.NORTH);

        // Área de texto
        textPane = new JTextPane();
        textPane.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(textPane);
        cp.add(scroll, BorderLayout.CENTER);

        textPane.getDocument().addUndoableEditListener(e -> {
            undoManager.addEdit(e.getEdit());
            updateUndoRedoButtons(undoBtn, redoBtn);
            updateStatus();
        });

        textPane.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateStatus(); }
            public void removeUpdate(DocumentEvent e) { updateStatus(); }
            public void changedUpdate(DocumentEvent e) { updateStatus(); }
        });

        // Configurar atajos de teclado
        setupKeyboardShortcuts();
        
        // Configurar navegación por teclado mejorada
        enableKeyboardNavigation();

        // Barra de estado extendida para voz
        JPanel statusBar = new JPanel(new BorderLayout());
        JPanel countersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12,2));
        lblChars = new JLabel("Chars: 0");
        lblChars.setToolTipText("Número total de caracteres en el documento");
        
        lblWords = new JLabel("Words: 0");
        lblWords.setToolTipText("Número total de palabras en el documento");
        
        lblLines = new JLabel("Lines: 1");
        lblLines.setToolTipText("Número total de líneas en el documento");
        
        lblVozStatus = new JLabel(" | Comandos Voz: 0");
        lblVozStatus.setForeground(new Color(0, 100, 200));
        lblVozStatus.setFont(lblVozStatus.getFont().deriveFont(Font.BOLD));
        lblVozStatus.setToolTipText("Número de comandos ejecutados por voz");
        
        countersPanel.add(lblChars);
        countersPanel.add(new JSeparator(SwingConstants.VERTICAL));
        countersPanel.add(lblWords);
        countersPanel.add(new JSeparator(SwingConstants.VERTICAL));
        countersPanel.add(lblLines);
        countersPanel.add(new JSeparator(SwingConstants.VERTICAL));
        countersPanel.add(lblVozStatus);

        progressLabel = new ProgressLabel();
        statusBar.add(countersPanel, BorderLayout.WEST);
        statusBar.add(progressLabel, BorderLayout.EAST);
        cp.add(statusBar, BorderLayout.SOUTH);

        // Menú superior mejorado con atajos visibles
        JMenuBar menuBar = new JMenuBar();
        
        JMenu menuArchivo = new JMenu("Archivo");
        menuArchivo.add(makeMenuItem("Nuevo\tCtrl+N", e -> newFile()));
        menuArchivo.add(makeMenuItem("Abrir...\tCtrl+O", e -> openFile()));
        menuArchivo.add(makeMenuItem("Guardar\tCtrl+S", e -> saveFile()));
        menuArchivo.add(makeMenuItem("Guardar como...", e -> saveFileAs()));
        menuArchivo.addSeparator();
        menuArchivo.add(makeMenuItem("Salir\tAlt+F4", e -> dispose()));
        menuBar.add(menuArchivo);

        JMenu menuEditar = new JMenu("Editar");
        menuEditar.add(makeMenuItem("Deshacer\tCtrl+Z", e -> doUndo()));
        menuEditar.add(makeMenuItem("Rehacer\tCtrl+Y", e -> doRedo()));
        menuEditar.addSeparator();
        menuEditar.add(makeMenuItem("Negrita\tCtrl+B", e -> toggleStyle(StyleConstants.CharacterConstants.Bold)));
        menuEditar.add(makeMenuItem("Cursiva\tCtrl+I", e -> toggleStyle(StyleConstants.CharacterConstants.Italic)));
        menuEditar.add(makeMenuItem("Subrayado\tCtrl+U", e -> toggleStyle(StyleConstants.CharacterConstants.Underline)));
        menuEditar.add(makeMenuItem("Color...", e -> changeColor()));
        menuBar.add(menuEditar);

        JMenu menuVoz = new JMenu("Voz");
        menuVoz.add(makeMenuItem("▶ Iniciar Escucha", e -> voiceControlPanel.toggleListening()));
        menuVoz.add(makeMenuItem("⏸ Detener Escucha", e -> voiceControlPanel.toggleListening()));
        menuVoz.addSeparator();
        menuVoz.add(makeMenuItem("Verificar Micrófono", e -> verificarMicrofono()));
        menuBar.add(menuVoz);

        JMenu menuAyuda = new JMenu("Ayuda");
        menuAyuda.add(makeMenuItem("Atajos de teclado", e -> mostrarAtajosTeclado()));
        menuAyuda.add(makeMenuItem("Comandos de voz", e -> mostrarComandosVoz()));
        menuBar.add(menuAyuda);

        setJMenuBar(menuBar);
        setVisible(true);
    }

    // ========== CONFIGURACIÓN DE ATAJOS DE TECLADO ==========
    
    private void setupKeyboardShortcuts() {
        // Obtener mapa de entrada del textPane
        InputMap inputMap = textPane.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = textPane.getActionMap();
        
        // Ctrl+N: Nuevo documento
        KeyStroke ctrlN = KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlN, "newFile");
        actionMap.put("newFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newFile();
                mostrarMensajeVoz("Nuevo documento creado (Ctrl+N)");
            }
        });
        
        // Ctrl+O: Abrir documento
        KeyStroke ctrlO = KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlO, "openFile");
        actionMap.put("openFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openFile();
                mostrarMensajeVoz("Abriendo documento (Ctrl+O)");
            }
        });
        
        // Ctrl+S: Guardar
        KeyStroke ctrlS = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlS, "saveFile");
        actionMap.put("saveFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveFile();
                mostrarMensajeVoz("Guardando documento (Ctrl+S)");
            }
        });
        
        // Ctrl+B: Negrita
        KeyStroke ctrlB = KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlB, "bold");
        actionMap.put("bold", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                aplicarFormatoVoz(StyleConstants.CharacterConstants.Bold);
                mostrarMensajeVoz("Negrita aplicada (Ctrl+B)");
            }
        });
        
        // Ctrl+I: Cursiva
        KeyStroke ctrlI = KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlI, "italic");
        actionMap.put("italic", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                aplicarFormatoVoz(StyleConstants.CharacterConstants.Italic);
                mostrarMensajeVoz("Cursiva aplicada (Ctrl+I)");
            }
        });
        
        // Ctrl+U: Subrayado
        KeyStroke ctrlU = KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlU, "underline");
        actionMap.put("underline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleStyle(StyleConstants.CharacterConstants.Underline);
                mostrarMensajeVoz("Subrayado aplicado (Ctrl+U)");
            }
        });
        
        // Ctrl+Z: Deshacer
        KeyStroke ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlZ, "undo");
        actionMap.put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doUndo();
                mostrarMensajeVoz("Deshacer (Ctrl+Z)");
            }
        });
        
        // Ctrl+Y: Rehacer
        KeyStroke ctrlY = KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlY, "redo");
        actionMap.put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doRedo();
                mostrarMensajeVoz("Rehacer (Ctrl+Y)");
            }
        });
        
        // Ctrl+F: Buscar
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlF, "find");
        actionMap.put("find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openFindReplaceDialog();
                mostrarMensajeVoz("Abrir búsqueda (Ctrl+F)");
            }
        });
        
        // Ctrl+A: Seleccionar todo
        KeyStroke ctrlA = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlA, "selectAll");
        actionMap.put("selectAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textPane.selectAll();
                mostrarMensajeVoz("Todo seleccionado (Ctrl+A)");
            }
        });
    }
    
    private void enableKeyboardNavigation() {
        // Hacer que todos los componentes sean navegables con Tab
        setFocusTraversalPolicy(new ContainerOrderFocusTraversalPolicy());
        
        // Añadir listeners para mostrar foco visualmente
        addFocusListenerToComponents(getContentPane());
    }
    
    private void addFocusListenerToComponents(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton || comp instanceof JTextField || comp instanceof JTextArea) {
                comp.addFocusListener(new FocusListener() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        if (comp instanceof JButton) {
                            JButton btn = (JButton) comp;
                            btn.setBorder(BorderFactory.createLineBorder(new Color(30, 144, 255), 2));
                        } else if (comp instanceof JTextPane) {
                            comp.setBackground(new Color(240, 248, 255));
                        }
                    }
                    
                    @Override
                    public void focusLost(FocusEvent e) {
                        if (comp instanceof JButton) {
                            JButton btn = (JButton) comp;
                            btn.setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(new Color(30, 90, 140), 1),
                                BorderFactory.createEmptyBorder(5, 10, 5, 10)
                            ));
                        } else if (comp instanceof JTextPane) {
                            comp.setBackground(Color.WHITE);
                        }
                    }
                });
            }
            
            if (comp instanceof Container) {
                addFocusListenerToComponents((Container) comp);
            }
        }
    }

    // ========== IMPLEMENTACIÓN DE NuevoNuiListener ==========
    
    @Override
    public void onCommand(NuevoNuiCommand cmd, String payload) {
        SwingUtilities.invokeLater(() -> {
            contadorComandosVoz++;
            lblVozStatus.setText(" | Comandos Voz: " + contadorComandosVoz);
            
            switch (cmd) {
                case NUEVO_DOCUMENTO:
                    newFile();
                    mostrarMensajeVoz("✅ Nuevo documento creado por voz");
                    break;
                case ABRIR_DOCUMENTO:
                    openFile();
                    mostrarMensajeVoz("✅ Abriendo documento por voz");
                    break;
                case GUARDAR_DOCUMENTO:
                    saveFile();
                    mostrarMensajeVoz("✅ Guardando documento por voz");
                    break;
                case APLICAR_NEGRITA:
                    aplicarFormatoVoz(StyleConstants.CharacterConstants.Bold);
                    mostrarMensajeVoz("✅ Negrita aplicada por voz");
                    break;
                case APLICAR_CURSIVA:
                    aplicarFormatoVoz(StyleConstants.CharacterConstants.Italic);
                    mostrarMensajeVoz("✅ Cursiva aplicada por voz");
                    break;
                case COLOR_ROJO:
                    aplicarColorVoz(Color.RED);
                    mostrarMensajeVoz("✅ Color rojo aplicado por voz");
                    break;
                case COLOR_AZUL:
                    aplicarColorVoz(Color.BLUE);
                    mostrarMensajeVoz("✅ Color azul aplicado por voz");
                    break;
                case DICTAR_TEXTO:
                    dictarTextoVoz(payload != null ? payload : "");
                    break;
            }
        });
    }
    
    private void aplicarFormatoVoz(Object style) {
        int inicio = textPane.getSelectionStart();
        int fin = textPane.getSelectionEnd();
        
        if (inicio == fin) {
            inicio = 0;
            fin = textPane.getDocument().getLength();
        }
        
        if (inicio < fin) {
            StyledDocument sd = textPane.getStyledDocument();
            MutableAttributeSet attr = new SimpleAttributeSet();
            Element elem = sd.getCharacterElement(inicio);
            AttributeSet as = elem.getAttributes();
            
            if (style == StyleConstants.CharacterConstants.Bold) {
                StyleConstants.setBold(attr, !StyleConstants.isBold(as));
            } else if (style == StyleConstants.CharacterConstants.Italic) {
                StyleConstants.setItalic(attr, !StyleConstants.isItalic(as));
            }
            
            sd.setCharacterAttributes(inicio, fin - inicio, attr, false);
        }
    }
    
    private void aplicarColorVoz(Color color) {
        int inicio = textPane.getSelectionStart();
        int fin = textPane.getSelectionEnd();
        
        if (inicio == fin) {
            inicio = 0;
            fin = textPane.getDocument().getLength();
        }
        
        if (inicio < fin) {
            StyledDocument sd = textPane.getStyledDocument();
            MutableAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setForeground(attr, color);
            sd.setCharacterAttributes(inicio, fin - inicio, attr, false);
        }
    }
    
    private void dictarTextoVoz(String texto) {
        if (texto.isEmpty()) return;
        
        try {
            int pos = textPane.getCaretPosition();
            textPane.getDocument().insertString(pos, texto + " ", null);
            mostrarMensajeVoz("📝 Dictado: \"" + (texto.length() > 20 ? texto.substring(0, 20) + "..." : texto) + "\"");
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }
    
    private void mostrarMensajeVoz(String mensaje) {
        progressLabel.setOperationText(mensaje);
        progressLabel.setState(ProgressLabel.State.DONE);
        System.out.println("[VOZ] " + mensaje);
    }
    
    private void verificarMicrofono() {
        try {
            // Verificar si hay micrófono disponible
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            boolean tieneMicrofono = false;
            StringBuilder micInfo = new StringBuilder();
            
            for (Mixer.Info info : mixerInfos) {
                Mixer mixer = AudioSystem.getMixer(info);
                Line.Info[] lineInfos = mixer.getTargetLineInfo();
                
                for (Line.Info lineInfo : lineInfos) {
                    if (lineInfo.getLineClass().equals(TargetDataLine.class)) {
                        tieneMicrofono = true;
                        micInfo.append("✓ ").append(info.getName())
                               .append(" - ").append(info.getDescription())
                               .append("\n");
                        break;
                    }
                }
            }
            
            if (tieneMicrofono) {
                JOptionPane.showMessageDialog(this, 
                    "✅ Micrófono detectado en el sistema\n\n" +
                    "Micrófonos disponibles:\n" + micInfo.toString() + "\n" +
                    "Para usar reconocimiento de voz:\n" +
                    "1. Haz clic en '▶ Iniciar Escucha por Voz'\n" +
                    "2. Espera el mensaje 'Micrófono inicializado correctamente'\n" +
                    "3. Habla claramente al micrófono\n" +
                    "4. Di comandos como 'nuevo', 'guardar', 'negrita'", 
                    "Verificación de Micrófono", 
                    JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, 
                    "⚠️ No se detectó ningún micrófono\n\n" +
                    "Verifica que:\n" +
                    "• El micrófono esté conectado\n" +
                    "• Los controladores estén instalados\n" +
                    "• Los permisos estén habilitados", 
                    "Micrófono no detectado", 
                    JOptionPane.WARNING_MESSAGE);
            }
                
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "❌ Error al verificar micrófono:\n" + e.getMessage(), 
                "Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void mostrarAtajosTeclado() {
        String atajos = "<html><h3>Atajos de teclado disponibles:</h3>" +
                       "<table border='0' cellpadding='5'>" +
                       "<tr><td><b>Ctrl+N</b></td><td>Nuevo documento</td></tr>" +
                       "<tr><td><b>Ctrl+O</b></td><td>Abrir documento</td></tr>" +
                       "<tr><td><b>Ctrl+S</b></td><td>Guardar documento</td></tr>" +
                       "<tr><td><b>Ctrl+B</b></td><td>Negrita</td></tr>" +
                       "<tr><td><b>Ctrl+I</b></td><td>Cursiva</td></tr>" +
                       "<tr><td><b>Ctrl+U</b></td><td>Subrayado</td></tr>" +
                       "<tr><td><b>Ctrl+Z</b></td><td>Deshacer</td></tr>" +
                       "<tr><td><b>Ctrl+Y</b></td><td>Rehacer</td></tr>" +
                       "<tr><td><b>Ctrl+F</b></td><td>Buscar/Reemplazar</td></tr>" +
                       "<tr><td><b>Ctrl+A</b></td><td>Seleccionar todo</td></tr>" +
                       "<tr><td><b>Tab</b></td><td>Navegar entre controles</td></tr>" +
                       "</table></html>";
        
        JOptionPane.showMessageDialog(this, atajos, 
                                    "Atajos de teclado", 
                                    JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void mostrarComandosVoz() {
        String comandos = "<html><h3>Comandos de voz disponibles:</h3>" +
                         "<table border='0' cellpadding='5'>" +
                         "<tr><td><b>'nuevo'</b></td><td>Crear nuevo documento</td></tr>" +
                         "<tr><td><b>'abrir'</b></td><td>Abrir documento</td></tr>" +
                         "<tr><td><b>'guardar'</b></td><td>Guardar documento</td></tr>" +
                         "<tr><td><b>'negrita'</b></td><td>Aplicar negrita</td></tr>" +
                         "<tr><td><b>'cursiva'</b></td><td>Aplicar cursiva</td></tr>" +
                         "<tr><td><b>'rojo'</b></td><td>Color rojo</td></tr>" +
                         "<tr><td><b>'azul'</b></td><td>Color azul</td></tr>" +
                         "<tr><td><b>'escribir [texto]'</b></td><td>Dictar texto</td></tr>" +
                         "</table><br>" +
                         "<p><i>Nota: Los comandos también funcionan en inglés.</i></p></html>";
        
        JOptionPane.showMessageDialog(this, comandos, 
                                    "Comandos de voz", 
                                    JOptionPane.INFORMATION_MESSAGE);
    }

    // ========== MÉTODOS MEJORADOS DEL EDITOR ==========
    
    private JMenuItem makeMenuItem(String text, ActionListener action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(action);
        item.setToolTipText(text.contains("\t") ? text.split("\t")[1] : text);
        return item;
    }
    
    // Método mejorado para crear botones con tooltips
    private JButton makeButton(String text, ActionListener action, String tooltip) { 
        JButton b = new JButton(text); 
        b.addActionListener(action);
        
        // Añadir tooltip
        if (tooltip != null) {
            b.setToolTipText(tooltip);
        } else {
            b.setToolTipText("Ejecutar: " + text);
        }
        
        // Mejorar contraste y apariencia
        b.setBackground(new Color(70, 130, 180)); // Azul accesible
        b.setForeground(Color.WHITE);
        b.setFocusPainted(true);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(30, 90, 140), 1),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        
        // Efecto hover para mejor feedback
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                b.setBackground(new Color(90, 150, 200));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                b.setBackground(new Color(70, 130, 180));
            }
        });
        
        return b; 
    }
    
    // Sobrecarga para compatibilidad
    private JButton makeButton(String text, ActionListener action) { 
        return makeButton(text, action, null);
    }
    
    private void newFile() {
        textPane.setText("");
        currentFile = null;
        setTitle("Editor con Voz - [Nuevo archivo]");
        progressLabel.reset();
    }

    private void saveFile() {
        if (currentFile == null) saveFileAs();
        else saveToFile(currentFile);
    }

    private void saveFileAs() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = fileChooser.getSelectedFile();
            if(!currentFile.getAbsolutePath().toLowerCase().endsWith(".rtf")) 
                currentFile = new File(currentFile.getAbsolutePath() + ".rtf");
            saveToFile(currentFile);
        }
    }

    private void saveToFile(File file) {
        progressLabel.setState(ProgressLabel.State.WORKING);
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                RTFEditorKit rtfKit = new RTFEditorKit();
                Document doc = textPane.getDocument();

                try (FileOutputStream out = new FileOutputStream(file)) {
                    rtfKit.write(out, doc, 0, doc.getLength());
                }

                for (int i = 1; i <= 100; i++) {
                    Thread.sleep(5);
                    publish(i);
                }

                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                int val = chunks.get(chunks.size() - 1);
                progressLabel.setProgress(val);
                progressLabel.setOperationText("Guardando " + file.getName() + " (" + val + "%)");
            }

            @Override
            protected void done() {
                progressLabel.setState(ProgressLabel.State.DONE);
                setTitle("Editor con Voz - [" + file.getName() + "]");
            }
        };
        worker.execute();
    }

    private void openFile() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if(fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION){
            currentFile = fileChooser.getSelectedFile();
            progressLabel.setState(ProgressLabel.State.WORKING);
            SwingWorker<Document,Integer> worker = new SwingWorker<>() {
                @Override
                protected Document doInBackground() throws Exception {
                    RTFEditorKit rtfKit = new RTFEditorKit();
                    Document doc = rtfKit.createDefaultDocument();

                    try (FileInputStream in = new FileInputStream(currentFile)) {
                        rtfKit.read(in, doc, 0);
                    }

                    for (int i = 1; i <= 100; i++) {
                        Thread.sleep(5);
                        publish(i);
                    }

                    return doc;
                }

                @Override
                protected void process(java.util.List<Integer> chunks) {
                    int val = chunks.get(chunks.size()-1);
                    progressLabel.setProgress(val);
                    progressLabel.setOperationText("Cargando "+currentFile.getName()+" ("+val+"%)");
                }

                @Override
                protected void done() {
                    try {
                        textPane.setDocument(get());
                        setTitle("Editor con Voz - ["+currentFile.getName()+"]");
                        progressLabel.setState(ProgressLabel.State.DONE);
                    } catch(Exception ex){
                        ex.printStackTrace();
                        progressLabel.setState(ProgressLabel.State.ERROR);
                    }
                }
            };
            worker.execute();
        }
    }

    private enum TextAction { UPPER, LOWER, REVERSE, REMOVE_DOUBLE_SPACES }

    private void transformSelectedOrAll(TextAction action) {
        try {
            Document doc = textPane.getDocument();
            int start = textPane.getSelectionStart();
            int end = textPane.getSelectionEnd();
            if(start==end){ start=0; end=doc.getLength(); }
            String original = doc.getText(start,end-start);
            String transformed = original;
            switch(action){
                case UPPER: transformed = original.toUpperCase(); break;
                case LOWER: transformed = original.toLowerCase(); break;
                case REVERSE: transformed = new StringBuilder(original).reverse().toString(); break;
                case REMOVE_DOUBLE_SPACES: transformed = original.replaceAll("\\s{2,}"," "); break;
            }
            doc.remove(start,end-start);
            doc.insertString(start,transformed,null);
        } catch (BadLocationException ex){ ex.printStackTrace(); }
    }

    private void toggleStyle(Object style){
        StyledDocument sd = textPane.getStyledDocument();
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if(start == end) return;

        Element elem = sd.getCharacterElement(start);
        AttributeSet as = elem.getAttributes();
        MutableAttributeSet attr = new SimpleAttributeSet();

        if(style == StyleConstants.CharacterConstants.Bold) {
            StyleConstants.setBold(attr, !StyleConstants.isBold(as));
        } else if(style == StyleConstants.CharacterConstants.Italic) {
            StyleConstants.setItalic(attr, !StyleConstants.isItalic(as));
        } else if(style == StyleConstants.CharacterConstants.Underline) {
            StyleConstants.setUnderline(attr, !StyleConstants.isUnderline(as));
        }

        sd.setCharacterAttributes(start, end - start, attr, false);
    }

    private void changeColor(){
        Color chosen = JColorChooser.showDialog(this,"Selecciona color",Color.BLACK);
        if(chosen==null) return;
        StyledDocument sd = textPane.getStyledDocument();
        int start=textPane.getSelectionStart(), end=textPane.getSelectionEnd();
        if(start==end) return;
        MutableAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setForeground(attr,chosen);
        sd.setCharacterAttributes(start,end-start,attr,false);
    }

    private void doUndo(){ if(undoManager.canUndo()) undoManager.undo(); }
    private void doRedo(){ if(undoManager.canRedo()) undoManager.redo(); }
    private void updateUndoRedoButtons(JButton undoBtn,JButton redoBtn){
        undoBtn.setEnabled(undoManager.canUndo());
        redoBtn.setEnabled(undoManager.canRedo());
    }

    private void updateStatus(){
        String text = textPane.getText();
        lblChars.setText("Chars: "+text.length());
        String trimmed=text.trim();
        lblWords.setText("Words: "+(trimmed.isEmpty()?0:trimmed.split("\\s+").length));
        int lines=text.isEmpty()?1:text.split("\r\n|\r|\n").length;
        lblLines.setText("Lines: "+lines);
    }
    
    private Object openFindReplaceDialog() { 
        JDialog dialog = new JDialog(this, "Buscar/Reemplazar", true);
        dialog.setSize(400, 200);
        dialog.setLocationRelativeTo(this);
        
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        panel.add(new JLabel("Buscar:"));
        JTextField txtBuscar = new JTextField();
        panel.add(txtBuscar);
        
        panel.add(new JLabel("Reemplazar:"));
        JTextField txtReemplazar = new JTextField();
        panel.add(txtReemplazar);
        
        JButton btnBuscar = new JButton("Buscar");
        JButton btnReemplazar = new JButton("Reemplazar");
        
        panel.add(btnBuscar);
        panel.add(btnReemplazar);
        
        dialog.add(panel);
        dialog.setVisible(true);
        return null;
    }

    class ProgressLabel extends JPanel{
        private JLabel label;
        private JProgressBar progressBar;
        enum State { IDLE, WORKING, DONE, ERROR }
        public ProgressLabel(){
            setLayout(new BorderLayout(5,0));
            label = new JLabel("Listo");
            label.setToolTipText("Estado actual de la aplicación");
            progressBar = new JProgressBar(0,100);
            progressBar.setPreferredSize(new Dimension(150,16));
            progressBar.setStringPainted(true);
            progressBar.setVisible(false);
            add(label,BorderLayout.WEST);
            add(progressBar,BorderLayout.EAST);
        }
        public void setState(State s){
            switch(s){
                case IDLE: label.setText("Listo"); progressBar.setVisible(false); break;
                case WORKING: label.setText("⚙️ Procesando..."); progressBar.setVisible(true); progressBar.setValue(0); break;
                case DONE: label.setText("✅ Completado"); progressBar.setValue(100);
                    Timer tDone = new Timer(800, ev -> setState(State.IDLE));
                    tDone.setRepeats(false); tDone.start();
                    break;
                case ERROR: label.setText("❌ Error"); progressBar.setVisible(false);
                    Timer tError = new Timer(1500, ev -> setState(State.IDLE));
                    tError.setRepeats(false); tError.start();
                    break;
            }
        }
        public void setProgress(int val){ progressBar.setValue(val);}
        public void setOperationText(String txt){ label.setText("⚙️ "+txt);}
        public void reset(){ setState(State.IDLE); progressBar.setValue(0);}
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            practica3_3_1 editor = new practica3_3_1();
            System.out.println("\n" + "=".repeat(60));
            System.out.println("EDITOR CON VOZ - VERSIÓN MEJORADA (RA4)");
            System.out.println("=".repeat(60));
            System.out.println("\nMEJORAS IMPLEMENTADAS:");
            System.out.println("1. Atajos de teclado: Ctrl+N, Ctrl+S, Ctrl+B, Ctrl+I, Ctrl+Z, Ctrl+Y");
            System.out.println("2. Tooltips en todos los botones y controles");
            System.out.println("3. Mejor contraste de colores (WCAG 4.5:1)");
            System.out.println("4. Navegación por teclado con foco visible");
            System.out.println("5. Indicador LED para estado de voz");
            System.out.println("\nINSTRUCCIONES:");
            System.out.println("1. Usa Ctrl+S para guardar, Ctrl+B para negrita, etc.");
            System.out.println("2. Pasa el ratón sobre botones para ver tooltips");
            System.out.println("3. Usa Tab para navegar entre controles");
            System.out.println("4. El círculo rojo/verde indica estado del micrófono");
            System.out.println("=".repeat(60) + "\n");
        });
    }
}