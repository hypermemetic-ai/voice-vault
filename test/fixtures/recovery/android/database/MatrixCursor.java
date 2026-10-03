package android.database;public class MatrixCursor implements Cursor {public Object[] row;public MatrixCursor(String[] columns){}public void addRow(Object[] row){this.row=row;}}
