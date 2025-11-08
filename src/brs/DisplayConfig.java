package brs;

public class DisplayConfig {
    private boolean showTestButtons = false;
    private boolean showMetricsPanel = false;
    private boolean showSpecialButtons = false;

    public boolean isShowTestButtons() {
        return showTestButtons;
    }

    public void setShowTestButtons(boolean showTestButtons) {
        this.showTestButtons = showTestButtons;
    }

    public boolean isShowMetricsPanel() {
        return showMetricsPanel;
    }

    public void setShowMetricsPanel(boolean showMetricsPanel) {
        this.showMetricsPanel = showMetricsPanel;
    }

    public boolean isShowSpecialButtons() {
        return showSpecialButtons;
    }

    public void setShowSpecialButtons(boolean showSpecialButtons) {
        this.showSpecialButtons = showSpecialButtons;
    }
}