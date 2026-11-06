package com.osrsbestinslot.export;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.PluginPanel;
import java.awt.Image;

final class FlipperPanel extends PluginPanel
{
    private static final Color GOLD = new Color(213, 181, 104);
    private static final Color DIM = new Color(163, 169, 171);
    private final JCheckBox toggle = new JCheckBox("Flipper");
    private final JLabel status = new JLabel();
    private final JLabel item = new JLabel();
    private final JLabel offer = new JLabel();
    private final JLabel source = new JLabel();
    private final JLabel profit = new JLabel();
    private final JLabel tax = new JLabel();
    private final JLabel perItem = new JLabel();
    private final JLabel breakEven = new JLabel();
    private final JLabel proceeds = new JLabel();
    private final JTextField cost = new JTextField();
    private long selectionKey;
    private boolean updating;

    FlipperPanel(BufferedImage logo, Consumer<Boolean> changeToggle, BiConsumer<Long, String> changeCost)
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 9, 10, 9));
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        heading.add(new JLabel(new ImageIcon(logo.getScaledInstance(90, 48, Image.SCALE_SMOOTH))),
            BorderLayout.WEST);
        JLabel title = new JLabel("<html>Grand Exchange<br><small>Profit calculator</small></html>");
        heading.add(title, BorderLayout.CENTER);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55));
        add(heading);
        add(Box.createVerticalStrut(10));
        toggle.setFont(toggle.getFont().deriveFont(Font.BOLD, 14f));
        toggle.setForeground(GOLD);
        toggle.setOpaque(false);
        toggle.setAlignmentX(LEFT_ALIGNMENT);
        toggle.addActionListener(event ->
        {
            if (!updating)
            {
                changeToggle.accept(toggle.isSelected());
            }
        });
        add(toggle);
        add(Box.createVerticalStrut(12));
        addText(status, DIM);
        add(Box.createVerticalStrut(10));
        item.setFont(item.getFont().deriveFont(Font.BOLD));
        addText(item, Color.WHITE);
        addText(offer, DIM);
        add(Box.createVerticalStrut(12));
        addText(new JLabel("Purchase price / item (GP)"), DIM);
        cost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        cost.setToolTipText("Enter your purchase price. Leave blank to use a recorded buy average.");
        cost.setAlignmentX(LEFT_ALIGNMENT);
        cost.getDocument().addDocumentListener(new DocumentListener()
        {
            private void changed()
            {
                if (!updating)
                {
                    changeCost.accept(selectionKey, cost.getText());
                }
            }
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        add(cost);
        addText(source, DIM);
        add(Box.createVerticalStrut(16));
        addText(new JLabel("Profit if sold"), DIM);
        profit.setFont(profit.getFont().deriveFont(Font.BOLD, 18f));
        addText(profit, GOLD);
        add(Box.createVerticalStrut(10));
        addText(tax, DIM);
        addText(perItem, DIM);
        addText(breakEven, DIM);
        addText(proceeds, DIM);
        add(Box.createVerticalStrut(14));
        JLabel note = new JLabel("<html>Uses your entered selling price.<br>Completed sales are excluded.</html>");
        addText(note, DIM);
    }

    private void addText(JLabel label, Color color)
    {
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        add(label);
    }

    void showView(FlipperView view)
    {
        updating = true;
        try
        {
            toggle.setSelected(view.enabled);
            status.setText(html(view.status));
            item.setText(html(view.itemName));
            item.setVisible(view.item > 0);
            offer.setText(view.item > 0
                ? html(FlipperView.number(view.quantity) + " remaining at " + FlipperView.number(view.price)
                    + " GP" + (view.sold > 0 ? " · " + FlipperView.number(view.sold) + " already sold" : ""))
                : "");
            if (selectionKey != view.key || !cost.hasFocus())
            {
                cost.setText(view.manualInput);
            }
            selectionKey = view.key;
            cost.setEnabled(view.enabled && view.item > 0 && view.quantity > 0);
            source.setText(html(view.costSource + (view.cost == null ? ""
                : " · " + FlipperView.number(view.cost.average()) + " GP / item")));
            FlipperMath.Result result = view.result;
            profit.setText(result == null ? "—" : FlipperView.compactProfit(result.profit));
            profit.setForeground(result != null && result.profit != null && result.profit.signum() < 0
                ? new Color(236, 123, 117) : GOLD);
            profit.setToolTipText(result == null ? null : FlipperView.profit(result.profit));
            tax.setText(result == null ? "" : html("Tax: " + FlipperView.number(result.tax) + " GP ("
                + FlipperView.number(result.taxPerItem) + " / item)"));
            perItem.setText(result == null ? "" : html("Profit / item: " + FlipperView.profit(result.profitPerItem)));
            breakEven.setText(result == null ? "" : html("Break-even / item: "
                + (result.breakEven == null ? (view.cost == null ? "Unknown" : "Above offer limit")
                    : FlipperView.number(result.breakEven) + " GP")));
            proceeds.setText(result == null ? "" : html("After-tax proceeds: "
                + FlipperView.number(result.proceeds) + " GP"));
        }
        finally
        {
            updating = false;
            revalidate();
            repaint();
        }
    }

    private static String html(String text)
    {
        return "<html><div style='width:145px'>" + text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;") + "</div></html>";
    }
}
